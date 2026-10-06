package dev.starrk.wallet.transfer;

import java.util.UUID;

import dev.starrk.wallet.integration.AuthorizationClient;
import dev.starrk.wallet.shared.ApiException;
import dev.starrk.wallet.user.User;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final UserRepository users;
    private final TransferRepository transfers;
    private final AuthorizationClient authorization;

    public TransferService(UserRepository users, TransferRepository transfers, AuthorizationClient authorization) {
        this.users = users;
        this.transfers = transfers;
        this.authorization = authorization;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        var amount = request.value().setScale(2);
        var fingerprint = TransferFingerprint.from(request);

        var existing = findIdempotentResult(request.payer(), idempotencyKey, fingerprint);
        if (existing != null) {
            return existing;
        }

        if (request.payer().equals(request.payee())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Origem e destino devem ser diferentes.");
        }

        // A ordem fixa de aquisição dos locks evita deadlocks entre transferências opostas.
        User first = lockUser(Math.min(request.payer(), request.payee()));
        User second = lockUser(Math.max(request.payer(), request.payee()));
        User payer = first.getId().equals(request.payer()) ? first : second;
        User payee = first.getId().equals(request.payee()) ? first : second;

        // Recheck after acquiring the wallet locks so concurrent requests with the same payer serialize here.
        existing = findIdempotentResult(request.payer(), idempotencyKey, fingerprint);
        if (existing != null) {
            return existing;
        }

        if (payer.getType() == UserType.MERCHANT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Lojistas não podem enviar transferências.");
        }

        payer.getWallet().debit(amount);
        payee.getWallet().credit(amount);
        // Qualquer recusa ou indisponibilidade lança uma exceção e desfaz os dois saldos.
        authorization.authorize();

        var transfer = transfers.save(new Transfer(payer.getId(), payee.getId(), amount,
                idempotencyKey, fingerprint));
        return TransferResponse.from(transfer);
    }

    @Transactional(readOnly = true)
    public TransferResponse find(UUID id) {
        return TransferResponse.from(transfers.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transferência não encontrada.")));
    }

    private User lockUser(Long id) {
        return users.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuário não encontrado: " + id));
    }

    private TransferResponse findIdempotentResult(Long payerId, String idempotencyKey, String fingerprint) {
        var previous = transfers.findByPayerIdAndIdempotencyKey(payerId, idempotencyKey).orElse(null);
        if (previous == null) {
            return null;
        }
        if (!previous.getRequestFingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A chave de idempotência já foi usada com dados diferentes.");
        }
        return TransferResponse.from(previous);
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.codePointCount(0, idempotencyKey.length()) > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Informe uma chave de idempotência com 1 a 128 caracteres não vazios.");
        }
    }
}
