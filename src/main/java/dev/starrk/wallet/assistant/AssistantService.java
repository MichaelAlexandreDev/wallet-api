package dev.starrk.wallet.assistant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.starrk.wallet.transfer.TransferService;
import dev.starrk.wallet.user.UserRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    static final String REFUSAL_REPLY = "Não posso ajudar com esse pedido.";
    static final String EMPTY_REPLY = "Não consegui gerar uma resposta. Tente reformular o pedido.";

    private final ChatClient chat;
    private final LlmGateway gateway;
    private final UserRepository users;
    private final TransferService transfers;
    private final ModelProvider provider;
    private final String systemPrompt;

    AssistantService(ChatClient.Builder chat, LlmGateway gateway, UserRepository users,
            TransferService transfers, ModelProvider provider,
            @Value("classpath:prompts/assistant-system.md") Resource systemPrompt) {
        this.chat = chat.build();
        this.gateway = gateway;
        this.users = users;
        this.transfers = transfers;
        this.provider = provider;
        this.systemPrompt = read(systemPrompt);
    }

    public AssistantReply chat(long userId, String message) {
        var tools = new WalletTools(userId, users, transfers);
        ChatResponse response = gateway.call("chat", () -> chat.prompt()
                .system(systemPrompt)
                .user(message)
                .tools(tools)
                .options(provider.chatOptions())
                .call()
                .chatResponse());
        return new AssistantReply(replyText(response), tools.proposals());
    }

    private static String replyText(ChatResponse response) {
        var result = response.getResult();
        if (result == null) {
            return EMPTY_REPLY;
        }
        String finishReason = result.getMetadata().getFinishReason();
        if (finishReason != null && finishReason.equalsIgnoreCase("refusal")) {
            return REFUSAL_REPLY;
        }
        String text = result.getOutput().getText();
        return text == null || text.isBlank() ? EMPTY_REPLY : text.strip();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public record AssistantReply(String reply, List<TransferProposal> proposals) {
    }
}
