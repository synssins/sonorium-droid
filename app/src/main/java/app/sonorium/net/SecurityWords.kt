package app.sonorium.net

/**
 * The four security words for a certificate fingerprint (APP_API.md 2.2):
 * the first four bytes of the SHA-256, one word each. The list must match
 * the server's `sonorium/auth/wordlist.py` exactly, in the same order.
 */
object SecurityWords {
    fun forFingerprint(fingerprint: String): List<String> {
        require(fingerprint.length >= 8) { "Fingerprint too short" }
        return (0 until 4).map { i -> WORDS[fingerprint.substring(i * 2, i * 2 + 2).toInt(16)] }
    }

    internal val WORDS = listOf(
        "acorn", "actor", "adult", "agent", "alarm", "album", "alley", "amber",
        "angle", "ankle", "apple", "apron", "arrow", "atlas", "attic", "award",
        "bacon", "badge", "bagel", "baker", "banjo", "barn", "basil", "beach",
        "beard", "bench", "berry", "bike", "birch", "bison", "blade", "blank",
        "bloom", "board", "boat", "bonus", "boot", "bread", "brick", "bride",
        "brook", "brush", "bucket", "buddy", "bugle", "cabin", "cable", "cactus",
        "camel", "candy", "canoe", "cargo", "carpet", "castle", "cedar", "chair",
        "chalk", "cheese", "cherry", "chess", "chief", "cider", "cliff", "clock",
        "cloud", "clover", "coach", "coast", "cobra", "comet", "coral", "cotton",
        "couch", "crane", "crown", "cube", "daisy", "dance", "delta", "denim",
        "desk", "diary", "dingo", "disco", "dock", "dolphin", "donut", "dragon",
        "drum", "duck", "eagle", "earth", "easel", "echo", "elbow", "elder",
        "ember", "engine", "fable", "falcon", "fancy", "farm", "feast", "fence",
        "ferry", "fiddle", "field", "flag", "flame", "flute", "focus", "forest",
        "fossil", "fox", "frost", "fruit", "gadget", "garden", "garlic", "gecko",
        "giant", "ginger", "glass", "globe", "glove", "goose", "grape", "gravy",
        "guitar", "hammer", "harbor", "harp", "hazel", "heron", "hiker", "honey",
        "hornet", "hotel", "igloo", "inlet", "iris", "island", "ivory", "jacket",
        "jaguar", "jelly", "jewel", "jockey", "judge", "juice", "jungle", "kayak",
        "kettle", "kiosk", "kitten", "koala", "ladder", "lagoon", "lamp", "lemon",
        "lilac", "lion", "lizard", "llama", "lobster", "locket", "lotus", "lunar",
        "magnet", "mango", "maple", "marble", "meadow", "melon", "mint", "mirror",
        "mitten", "monkey", "moose", "mosaic", "motor", "muffin", "napkin", "nectar",
        "needle", "nickel", "noodle", "nutmeg", "oasis", "ocean", "olive", "onion",
        "opera", "orbit", "orchid", "otter", "owl", "oyster", "paddle", "panda",
        "parrot", "peach", "pebble", "pencil", "pepper", "piano", "pickle", "pilot",
        "pine", "planet", "plum", "pony", "poppy", "pretzel", "puffin", "pumpkin",
        "quail", "quartz", "quilt", "rabbit", "radar", "radish", "raven", "reef",
        "ribbon", "river", "robin", "rocket", "rose", "ruby", "saddle", "salmon",
        "sandal", "scarf", "shell", "silver", "sketch", "sled", "spoon", "squid",
        "stone", "sugar", "summit", "tango", "teapot", "tiger", "tomato", "torch",
        "tulip", "turtle", "velvet", "violin", "walnut", "whale", "willow", "zebra",
    )
}
