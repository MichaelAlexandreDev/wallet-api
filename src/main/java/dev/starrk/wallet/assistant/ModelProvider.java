package dev.starrk.wallet.assistant;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * O que muda entre provedores de LLM: opções da requisição, como pedir saída estruturada, se há
 * credenciais e quanto custa. O resto do assistente (ferramentas, prompts, regras) é o mesmo.
 */
interface ModelProvider {
    String name();

    /** Falso quando falta configuração obrigatória, como a chave da Anthropic. */
    boolean configured();

    String notConfiguredMessage();

    ChatOptions.Builder<?> chatOptions();

    /** Opções para uma resposta JSON que segue {@code schema}. */
    ChatOptions.Builder<?> structuredOptions(Map<String, Object> schema);

    BigDecimal inputUsdPerMillionTokens();

    BigDecimal outputUsdPerMillionTokens();
}
