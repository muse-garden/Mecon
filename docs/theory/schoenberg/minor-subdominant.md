# 小下属关系和弦、拿坡里与类拿坡里连接

> 状态：章节目录、和弦选择目录（拿坡里单列）、三个独立练习及自由练习贡献已接入。

教材来源为仓库根目录 `textbook-schonberg.md` 的“小下属关系和弦”章节。实现入口：

- `SchoenbergMinorSubdominantChapter.kt`
- `SchoenbergCurriculumCatalog.kt`
- `SchoenbergChapterRegistry.kt`

## 1. 构造边界

借用和弦词汇以及“小下属连接 / 类拿坡里”教材练习限定在大调母调；拿坡里终止关系本身也可投影
到小调，供统一自由练习目录使用。大调借用词汇由两组自然小调音级和弦投影回母调：

1. 同主音自然小调；
2. 下属音上的自然小调。

详情构造谱例按教材的共同和弦关系显示灰色参照：同主音小调通过母调属和弦联系，下属小调通过
母调主和弦联系；参照与借入和弦联合选择紧凑音区，并优先避免额外加线。

例如 C 大调得到 c 小调与 f 小调中的借用和弦。重复音响按
`degree / rootAlteration / quality / arity` 合并；三和弦与七和弦均进入目录，共 10 个三和弦身份和
10 个七和弦身份。符号保留 `modalOrigins={AEOLIAN}`，目录解释挂
`SchoenbergHarmonicTreatments.MINOR_SUBDOMINANT`，实际拼写仍通过统一
`ConstructedChord → ChordCatalog` 管线归并。

本章不把借用和弦伪装成副属功能。副属 / 副导通过升号指向局部主音；小下属关系和弦是从降号侧借入，
两者保留不同解释与规则族。

选择目录中，拿坡里和弦通过本章节声明的 `ChordCatalogNamedSubset` 从小下属关系大类中抽出，
独立显示且不在父分组重复。识别条件仍是章节拥有的结构属性（降二级、大三和弦、三和弦），
自由练习与通用选择组件不识别“拿坡里”名称。

## 2. 独立练习

三个 exercise 均挂在“大调和弦连接”分支，并要求先枚举符号进行再固定四部排布：

- `schoenberg.minor-subdominant.connections`
- `schoenberg.minor-subdominant.neapolitan-cadence`
- `schoenberg.minor-subdominant.analogous-neapolitan`

### 2.1 小下属关系和弦连接

结构为“自然音级和弦—借用和弦—自然音级和弦”。当前先按教材建议从自然音级前后文开始；
中间借用和弦允许三和弦和七和弦。

枚举只保留能够陈述典型半音线条的组合：降音从同音级自然音下行半音引入，并继续下行到下一自然音级。
由于教材措辞为“尽量”，这两条在四部求解中是软偏好与成功说明，不是硬剪枝。根音方向判断继续委托
`SchoenbergRootMotionAndRepetitionChapter`；七音预备 / 解决继续委托
`SchoenbergSeventhChordChapter`，不在本章复制。

### 2.2 拿坡里和弦连接到终止式

枚举两种固定结构：

- `♭II6–I64–V–I`
- `♭II6–V–I`

前者要求降二级下行半音到主音、降六级下行半音到属音；后者允许教材明确列出的例外，
要求降二级下行减三度到导音，同时降六级仍下行半音到属音。终止后缀结构复用
`SchoenbergCadencePolicy`，一般四六和弦的“根音或低音保持预备”不套在终止四六上。

### 2.3 类拿坡里和弦连接

把已出现的大六和弦解释为某个调内局部主音的拿坡里和弦，枚举：

`大六和弦–局部 I64–局部 V`

连接关系本体必须满足：

- 大六和弦根音在局部主音上方半音；
- 局部 I64 的低音保持到局部 V。

“局部主音属于母调自然三和弦、末槽属于当前副属目录”不再属于关系本体，而由可替换的
`SchoenbergProgressionVocabularyFilter` 决定。教材与自由练习当前都使用
`StudiedInActiveKey`，所以现有 C 大调枚举仍包含教材示例 `♭VI6–V64–V/V`，且结果集合不变；
领域级 `None` 过滤证明同一半音关系可用于非自然音局部目标，未来转调只需换成目标调过滤。

拿坡里终止式本身也进入自由练习默认目录；大小调均提供 `♭II6–V` 与
`♭II6–I64–V`。小调直接形式的降二级下行到升高导音，typed requirement 因而使用第 7 级的
`+1` alteration，而不是误指向自然小调降七级。

## 3. 规则分层

- 借用和弦是否出现、练习结构与局部功能由本章决定。
- 借用来源 `schoenberg.minor-subdominant.derivation` 是说明型 `Annotate`；投影到自由练习后仍作为
  HINT 展示，不参与软规则警告或求解评分。
- 三 / 七和弦完整性、音域、声部交叉、平行五八度由一般四部规则负责。
- 七音预备解决、终止式与副属构造继续使用既有章节本体。
- `ruleModules = emptyList()` 且 `includeDerivedTextbookConstraints = false`，避免旧 textbook
  针对具体和弦的命名规则与本章结构重复。

回归测试见
`theory/src/commonTest/kotlin/com/mecon/theory/schoenberg/SchoenbergMinorSubdominantChapterTest.kt`。
