package interstellarTikiTiks.apexx.client.module;

public enum SubCategory {
    MACE("Mace"),
    CRYSTAL("Crystal"),
    ANCHOR("Anchor"),
    CART("Cart"),
    SMP("SMP"),
    GENERAL("General");

    private final String name;

    SubCategory(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
