package com.mecon.theory.schoenberg

/**
 * 勋伯格综合练习的「禁忌相邻进行」表——某些两个和弦即使和声上讲得通，在四部写作里也永远写不出来
 * （例如 I→ii7 这类根位七和弦被上二度进入，三个声部只能同向级进 → 必出平行五度）。
 *
 * 这些禁忌**不写死在枚举代码里**：由 `SchoenbergForbiddenTransitionGeneratorTest` 用求解器逐对探测生成，
 * 写到 `theory/src/jvmMain/resources/schoenberg/` 下的人类可读数据文件，运行时由本对象读取。
 * 严格档与自由处理档分表，避免后续章节反向继承七音 / 减五度的严格禁忌。
 *
 * 判据在**同一调式内调性无关**（平行、间距等只看音程），故大调用 C 大调、小调用 a 小调各探测一次即可套用到所有调；
 * 键用 degree/quality/arity/position 表示。每个规则档位均分大调 / 小调：小调因升六、导音与增和弦等仍保留的
 * 旋律硬要求，禁忌相邻对与大调不同，见 [SchoenbergMinorChapter] 与对应生成器测试。
 */
object SchoenbergForbiddenTransitions {
    private val strictMajor by lazy { load(minor = false, SchoenbergForbiddenTransitionProfile.STRICT) }
    private val strictMinor by lazy { load(minor = true, SchoenbergForbiddenTransitionProfile.STRICT) }
    private val freeMajor by lazy { load(minor = false, SchoenbergForbiddenTransitionProfile.FREE) }
    private val freeMinor by lazy { load(minor = true, SchoenbergForbiddenTransitionProfile.FREE) }

    fun isForbidden(
        before: SchoenbergSymbolicChord,
        after: SchoenbergSymbolicChord,
        minor: Boolean,
        profile: SchoenbergForbiddenTransitionProfile = SchoenbergForbiddenTransitionProfile.STRICT,
    ): Boolean {
        val table = when (profile) {
            SchoenbergForbiddenTransitionProfile.STRICT -> if (minor) strictMinor else strictMajor
            SchoenbergForbiddenTransitionProfile.FREE -> if (minor) freeMinor else freeMajor
        }
        return (before.transitionToken() to after.transitionToken()) in table
    }

    private fun load(
        minor: Boolean,
        profile: SchoenbergForbiddenTransitionProfile,
    ): Set<Pair<String, String>> =
        parse(loadSchoenbergForbiddenTransitionResource(minor, profile))

    /** 供生成器与解析器共用的解析：忽略 `#` 注释与空行，每行 `<before> => <after>`（`#` 后是人类注记）。 */
    fun parse(text: String?): Set<Pair<String, String>> {
        if (text.isNullOrBlank()) return emptySet()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && "=>" in it }
            .mapNotNull { line ->
                val (before, after) = line.split("=>", limit = 2).map(String::trim)
                if (before.isEmpty() || after.isEmpty()) null else before to after
            }
            .toSet()
    }
}

/** 和弦的稳定标识：普通和弦保持旧格式；变化/副属和弦追加根音变化、功能族与目标音级。 */
fun SchoenbergSymbolicChord.transitionToken(): String {
    val positionName = seventhPosition?.name ?: position.name
    val base = "$degree/$quality/$arity/$positionName"
    if (
        secondaryFamily == null &&
        augmentedSixthFamily == null &&
        rootAlteration == 0 &&
        appliedToDegree == null
    ) return base
    val family = secondaryFamily?.name ?: augmentedSixthFamily?.let { "AUGMENTED_SIXTH_${it.name}" } ?: "ALTERED"
    val functional = "$base@$rootAlteration/$family/${appliedToDegree ?: 0}"
    return rootlessDominantNinthUsageId?.let { "$functional/rootless:$it" } ?: functional
}

/** 载入禁忌表数据文件（JVM 从 classpath 资源读取）；不存在时返回 null，禁忌表退化为空、枚举不额外剪枝。 */
enum class SchoenbergForbiddenTransitionProfile {
    STRICT,
    FREE,
}

internal expect fun loadSchoenbergForbiddenTransitionResource(
    minor: Boolean,
    profile: SchoenbergForbiddenTransitionProfile = SchoenbergForbiddenTransitionProfile.STRICT,
): String?
