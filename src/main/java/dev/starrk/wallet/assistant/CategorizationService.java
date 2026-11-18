package dev.starrk.wallet.assistant;

import java.util.UUID;

import dev.starrk.wallet.transfer.TransferCategory;
import dev.starrk.wallet.transfer.TransferRepository;
import dev.starrk.wallet.transfer.TransferService;
import dev.starrk.wallet.user.UserRepository;
import dev.starrk.wallet.user.UserService;
import org.springframework.stereotype.Service;

@Service
public class CategorizationService {
    private final TransferService transfers;
    private final TransferRepository repository;
    private final UserRepository users;
    private final TransactionCategorizer categorizer;

    public CategorizationService(TransferService transfers, TransferRepository repository, UserRepository users,
            TransactionCategorizer categorizer) {
        this.transfers = transfers;
        this.repository = repository;
        this.users = users;
        this.categorizer = categorizer;
    }

    /**
     * Categoriza uma transferência de que o usuário participou. A categoria é gravada uma vez; chamadas
     * seguintes devolvem a gravada sem consultar o modelo. Não há transação aberta durante a chamada ao LLM.
     */
    public CategoryResponse categorize(UUID id, long requesterId) {
        var transfer = transfers.find(id, requesterId);
        if (transfer.category() != null) {
            return new CategoryResponse(id, transfer.category(), TransactionCategorizer.Source.STORED);
        }
        var payee = users.findById(transfer.payee()).orElseThrow(UserService::notFound);
        var result = categorizer.categorize(new TransactionCategorizer.Input(transfer.description(),
                payee.getFullName(), payee.getType(), transfer.value()));
        if (repository.assignCategoryIfAbsent(id, result.category()) == 0) {
            // Outra requisição gravou primeiro: vale a categoria já gravada.
            return new CategoryResponse(id, transfers.find(id, requesterId).category(),
                    TransactionCategorizer.Source.STORED);
        }
        return new CategoryResponse(id, result.category(), result.source());
    }

    public record CategoryResponse(UUID transferId, TransferCategory category, TransactionCategorizer.Source source) {
    }
}
