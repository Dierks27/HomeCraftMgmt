package com.dierks.homecraft.mini;

/**
 * A mutable working copy of a Mini used by the Admin Studio edit form (records
 * are immutable, so the GUI mutates this and converts to a {@link MiniDef} on
 * save). {@code cap == -1} means uncapped; {@code price < 0} means "use the
 * rarity default" until the admin sets one.
 */
public final class MiniDraft {

    private String id;            // null until first save (auto-derived from name)
    private String name;
    private String series;
    private String category;
    private Rarity rarity;
    private MiniType type;
    private String texture;
    private long cap;
    private double price;
    private boolean craftable;

    public MiniDraft(String id, String name, String series, String category, Rarity rarity,
                     MiniType type, String texture, long cap, double price, boolean craftable) {
        this.id = id;
        this.name = name;
        this.series = series;
        this.category = category;
        this.rarity = rarity;
        this.type = type;
        this.texture = texture;
        this.cap = cap;
        this.price = price;
        this.craftable = craftable;
    }

    public static MiniDraft from(MiniDef def) {
        return new MiniDraft(def.id(), def.name(), def.series(), def.category(), def.rarity(),
                def.type(), def.texture(), def.cap(), def.price(), def.craftable());
    }

    /** Convert to an immutable {@link MiniDef}, deriving the id from the name if unset. */
    public MiniDef toDef() {
        String finalId = (id == null || id.isBlank()) ? MiniIds.slug(name) : id;
        return new MiniDef(finalId, name, series, category, rarity, type,
                texture == null ? "" : texture, cap, price, craftable);
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String series() {
        return series;
    }

    public void setSeries(String series) {
        this.series = series;
    }

    public String category() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Rarity rarity() {
        return rarity;
    }

    public void setRarity(Rarity rarity) {
        this.rarity = rarity;
    }

    public MiniType type() {
        return type;
    }

    public void setType(MiniType type) {
        this.type = type;
    }

    public String texture() {
        return texture;
    }

    public void setTexture(String texture) {
        this.texture = texture;
    }

    public long cap() {
        return cap;
    }

    public void setCap(long cap) {
        this.cap = cap;
    }

    public boolean uncapped() {
        return cap < 0;
    }

    public double price() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public boolean craftable() {
        return craftable;
    }

    public void setCraftable(boolean craftable) {
        this.craftable = craftable;
    }
}
