package dev.starrk.wallet.transfer;

/** Categorias de gasto, do ponto de vista de quem pagou. */
public enum TransferCategory {
    FOOD("Alimentação fora de casa: restaurantes, lanches, delivery, cafés e bares"),
    GROCERIES("Mercado, padaria, hortifrúti e compras de supermercado"),
    TRANSPORT("Transporte: aplicativos de corrida, táxi, ônibus, metrô, combustível, estacionamento, pedágio"),
    HOUSING("Moradia: aluguel, condomínio, financiamento do imóvel, reformas"),
    BILLS("Contas recorrentes: luz, água, gás, internet, telefone, assinaturas e streaming"),
    HEALTH("Saúde: farmácia, consultas, exames, plano de saúde, academia"),
    EDUCATION("Educação: escola, faculdade, cursos, livros didáticos, material escolar"),
    SHOPPING("Compras de produtos: roupas, eletrônicos, casa e decoração, presentes comprados em loja"),
    LEISURE("Lazer: cinema, shows, viagens, hospedagem, jogos e passeios"),
    PERSONAL_TRANSFER("Dinheiro entre pessoas: dividir conta, reembolso, empréstimo, mesada, presente em dinheiro"),
    OTHER("Nenhuma das anteriores, ou informação insuficiente para decidir");

    private final String meaning;

    TransferCategory(String meaning) {
        this.meaning = meaning;
    }

    public String meaning() {
        return meaning;
    }
}
