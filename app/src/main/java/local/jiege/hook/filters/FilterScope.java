package local.jiege.hook.filters;

/**
 * The two independently managed filter scopes. Each has its own catalog and its own filter groups
 * (static fields of both FilterGroupManager classes, SDK and app).
 */
public enum FilterScope {
    /** Every photo mode except master: photo, portrait, sticker, night. */
    NORMAL("sFilterGroup", "sPortraitFilterGroup", "sStickerFilterGroup", "sNightFilterGroup"),
    /** Master mode (mode name "professional"), all gears; its only group is the Pro group. */
    MASTER("sProFilterGroup");

    /** Mode name of master mode (BaseMode.T0). */
    public static final String MASTER_MODE = "professional";

    /**
     * Master mode on camera 7.006.103 is "professional"; on 7.006.121 "master" (大师) and
     * "professional" (专业) are two independent modes, and entering 大师 reports modeName
     * "master". A master-mode session is either name; the constant keeps its value because
     * other code relies on "professional" for the actual professional mode.
     */
    public static final String MASTER_MODE_121 = "master";

    /** True when the mode name is a master-mode session on either camera version. */
    public static boolean isMasterMode(Object modeName) {
        return MASTER_MODE.equals(modeName) || MASTER_MODE_121.equals(modeName);
    }

    final String[] groups;

    FilterScope(String... groups) {
        this.groups = groups;
    }

    /** The scope whose filters a mode shows. */
    public static FilterScope ofMode(Object modeName) {
        return isMasterMode(modeName) ? MASTER : NORMAL;
    }
}
