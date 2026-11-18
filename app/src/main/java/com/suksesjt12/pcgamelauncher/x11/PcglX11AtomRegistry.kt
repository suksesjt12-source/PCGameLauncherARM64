package com.suksesjt12.pcgamelauncher.x11

/**
 * Server-wide X11 atom table. X11 predefined atoms have fixed protocol IDs and
 * dynamically interned atoms must be shared by every client of the same server.
 */
internal class PcglX11AtomRegistry {
    private val idsByName = HashMap<String, Int>()
    private val namesById = HashMap<Int, String>()
    private var nextDynamicAtom = FIRST_DYNAMIC_ATOM

    init {
        PREDEFINED_ATOMS.forEachIndexed { index, name ->
            val atomId = index + 1
            idsByName[name] = atomId
            namesById[atomId] = name
        }
    }

    @Synchronized
    fun intern(name: String, onlyIfExists: Boolean): Int {
        idsByName[name]?.let { return it }
        if (onlyIfExists || nextDynamicAtom > MAX_ATOM_ID) return 0
        val atomId = nextDynamicAtom++
        idsByName[name] = atomId
        namesById[atomId] = name
        return atomId
    }

    @Synchronized
    fun name(atomId: Int): String? = namesById[atomId]

    private companion object {
        const val FIRST_DYNAMIC_ATOM = 69
        const val MAX_ATOM_ID = 0x7fffffff

        // X11 core predefined atoms, whose IDs are fixed by the protocol.
        val PREDEFINED_ATOMS = listOf(
            "PRIMARY", "SECONDARY", "ARC", "ATOM", "BITMAP", "CARDINAL",
            "COLORMAP", "CURSOR", "CUT_BUFFER0", "CUT_BUFFER1", "CUT_BUFFER2",
            "CUT_BUFFER3", "CUT_BUFFER4", "CUT_BUFFER5", "CUT_BUFFER6",
            "CUT_BUFFER7", "DRAWABLE", "FONT", "INTEGER", "PIXMAP", "POINT",
            "RECTANGLE", "RESOURCE_MANAGER", "RGB_COLOR_MAP", "RGB_BEST_MAP",
            "RGB_BLUE_MAP", "RGB_DEFAULT_MAP", "RGB_GRAY_MAP", "RGB_GREEN_MAP",
            "RGB_RED_MAP", "STRING", "VISUALID", "WINDOW", "WM_COMMAND",
            "WM_HINTS", "WM_CLIENT_MACHINE", "WM_ICON_NAME", "WM_ICON_SIZE",
            "WM_NAME", "WM_NORMAL_HINTS", "WM_SIZE_HINTS", "WM_ZOOM_HINTS",
            "MIN_SPACE", "NORM_SPACE", "MAX_SPACE", "END_SPACE", "SUPERSCRIPT_X",
            "SUPERSCRIPT_Y", "SUBSCRIPT_X", "SUBSCRIPT_Y", "UNDERLINE_POSITION",
            "UNDERLINE_THICKNESS", "STRIKEOUT_ASCENT", "STRIKEOUT_DESCENT",
            "ITALIC_ANGLE", "X_HEIGHT", "QUAD_WIDTH", "WEIGHT", "POINT_SIZE",
            "RESOLUTION", "COPYRIGHT", "NOTICE", "FONT_NAME", "FAMILY_NAME",
            "FULL_NAME", "CAP_HEIGHT", "WM_CLASS", "WM_TRANSIENT_FOR"
        )
    }
}
