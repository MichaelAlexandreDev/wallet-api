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
    public TransferResponse transfer(TransferRequest request) {
        if (request.payer().equals(request.payee())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Origem e destino devem ser diferentes.");
        }

        // A ordem fixa de aquisição dos locks evita deadlocks entre transferências opostas.
        User first = lockUser(Math.min(request.payer(), request.payee()));
        User second = lockUser(Math.max(request.payer(), request.payee()));
        User payer = first.getId().equals(request.payer()) ? first : second;
        User payee = first.getId().equals(request.payee()) ? first : second;

        if (payer.getType() == UserType.MERCHANT) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Lojistas não podem enviar transferências.");
        }

        payer.getWallet().debit(request.value());
        payee.getWallet().credit(request.value());
        // Qualquer recusa ou indisponibilidade lança uma exceção e desfaz os dois saldos.
        authorization.authorize();

        var transfer = transfers.save(new Transfer(payer.getId(), payee.getId(), request.value()));
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
}
