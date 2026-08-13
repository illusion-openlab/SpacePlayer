# 宽高比启发式视频格式检测 — 设计稿

日期:2026-08-13

## 背景与动机

`FormatDetector`(`app/src/main/java/tech/illusion/spaceplayer/library/FormatDetector.kt`)目前只有两层识别:

1. 容器探测(`MultiviewTrackProbe`):数视频轨道里同分辨率 HEVC 轨道数是否 ≥2,命中就认为是 MV-HEVC 多视图立体格式。
2. 文件名关键词(`FilenameFormatDetector`):`_180_`/`_360_`/`_sbs`/`_tb` 等关键词匹配。

完全没有看视频本身的几何信息(宽高)。不按命名规范来的素材只能落到默认兜底(平面·单目),即使它实际是 360° 或双目立体。

本设计新增第三层:从视频轨道的宽高比推测投影/立体格式,作为文件名检测之后的补缺信号,不改变现有识别的优先级和手动覆盖路径。

## 范围

- 覆盖:360° 等距柱状投影、180° 半球、双目左右(SBS)、双目上下(TB)。
- 不覆盖:MV-HEVC(已有容器探测覆盖,机制不同——多视图是"两条独立轨道",这里是"单轨道内画面被压成两半");不做 360°+立体组合帧(如 4:1 的 360° SBS)的专门识别,这类素材要么被 360° 规则命中(先命中先返回),要么留给文件名/手动覆盖兜底,不在这版范围内。
- 不做 MP4 Spherical Video V2 元数据(XMP/UUID box)解析——Android `MediaExtractor`/`MediaMetadataRetriever` 不直接暴露这个,需要手动解析原始 box 结构,工作量明显更大,这版先看宽高比够不够用。

## 架构与数据流

```
detect(context, uri, displayName)
  │
  ├─ 1. 容器探测:MultiviewTrackProbe.probe(context, uri)
  │      → ContainerProbeResult(isMultiview: Boolean, videoWidth: Int?, videoHeight: Int?)
  │      (原来只返回 Boolean，现在顺带带出主视频轨宽高——复用同一次
  │       MediaExtractor.setDataSource() 解析，不额外开一次文件)
  │
  ├─ 2. 若 isMultiview == true：
  │      projection = 文件名投影 ?: 宽高比投影 ?: FLAT
  │      stereoMode = MULTIVIEW_MVHEVC
  │      formatSource = DETECTED_CONTAINER
  │
  └─ 3. 否则：
         filenameHint = FilenameFormatDetector.detect(displayName)   // 见下方改动
         aspectHint   = AspectRatioFormatDetector.detect(width, height)
         projection  = filenameHint.projection ?: aspectHint?.projection ?: FLAT
         stereoMode  = filenameHint.stereoMode ?: aspectHint?.stereoMode ?: MONO
         formatSource = 按"谁实际填了至少一个字段"决定：
             filenameHint 任一字段非空 → DETECTED_FILENAME
             否则 aspectHint 非 null   → DETECTED_ASPECT_RATIO
             否则                      → DEFAULT
```

## 组件改动

### `MultiviewTrackProbe.kt`

接口从 `looksLikeMultiview(context, uri): Boolean` 改为 `probe(context, uri): ContainerProbeResult`：

```kotlin
data class ContainerProbeResult(
    val isMultiview: Boolean,
    val videoWidth: Int?,
    val videoHeight: Int?,
)
```

`videoWidth`/`videoHeight` 取遍历轨道时遇到的第一条视频轨(`mime` 以 `"video/"` 开头，不再限定 `video/hevc`——宽高比检测要对任意编码的视频都生效，多视图判断仍然只看 HEVC 轨道数)。找不到视频轨或探测异常时为 `null`，此时宽高比检测直接跳过（不猜）。

### `FilenameFormatDetector.kt`

返回值从"一个全填好默认值的 `DetectedFormat`（或 null）"改成两个独立可空字段：

```kotlin
data class FilenameHint(
    val projection: Projection?,
    val stereoMode: StereoMode?,
)

fun detect(displayName: String): FilenameHint
```

不再在内部把未命中的字段填成 `FLAT`/`MONO`——这正是这次改动要解决的问题：只有这样，`FormatDetector` 才能分辨"文件名真的没提到这个字段"和"文件名提到了但结果恰好是默认值"，从而让宽高比去补前者、不覆盖后者。

### 新增 `AspectRatioFormatDetector.kt`

纯函数，不依赖 Android API：

```kotlin
object AspectRatioFormatDetector {
    private const val EQUIRECT_360_MIN = 1.85f
    private const val EQUIRECT_360_MAX = 2.15f
    private const val HEMISPHERE_180_MIN = 0.9f
    private const val HEMISPHERE_180_MAX = 1.1f
    private const val NORMAL_VIDEO_RATIO_MIN = 1.3f
    private const val NORMAL_VIDEO_RATIO_MAX = 2.4f

    fun detect(width: Int, height: Int): DetectedFormat? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio in EQUIRECT_360_MIN..EQUIRECT_360_MAX ->
                DetectedFormat(Projection.SPHERE_360, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            ratio in HEMISPHERE_180_MIN..HEMISPHERE_180_MAX ->
                DetectedFormat(Projection.HEMISPHERE_180, StereoMode.MONO, FormatSource.DETECTED_ASPECT_RATIO)
            (width / 2f) / height in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.SIDE_BY_SIDE, FormatSource.DETECTED_ASPECT_RATIO)
            width / (height / 2f) in NORMAL_VIDEO_RATIO_MIN..NORMAL_VIDEO_RATIO_MAX ->
                DetectedFormat(Projection.FLAT, StereoMode.TOP_AND_DOWN, FormatSource.DETECTED_ASPECT_RATIO)
            else -> null
        }
    }
}
```

四个分支按顺序判断，命中即返回，不会重复分类。返回的 `DetectedFormat` 里未涉及的字段用中性默认值（`FLAT`/`MONO`）占位，`FormatDetector` 只取它里面真正相关的那个字段（SBS/TB 分支只借它的 `stereoMode`，360/180 分支只借它的 `projection`）。

阈值来源：360° 等距柱状投影常见分辨率（3840×1920、5760×2880、7680×3840）精确落在 2.0，容差覆盖重编码导致的零头像素；180° 单鱼眼素材常见是方形；SBS/TB 的容差区间（1.3~2.4）覆盖 4:3 到 21:9 超宽的"正常单眼画面"。这些都是纯函数里的常量，以后发现某类素材判错，改一个数字、加一个单测即可，不涉及架构变动。

**下限为什么是 1.3 不是 1.0**：设计过程中先定的是 1.0~2.4，手算测试用例时发现一个常规 9:16 竖屏平面视频（1080×1920）会被误判成 TOP_AND_DOWN——1080/(1920/2) = 1.125，恰好落进 1.0~2.4。竖屏视频是很常见的素材，这个误判代价不小，所以把下限提到 1.3（排掉近似正方形的"半高比"，同时仍覆盖 4:3=1.33 到 21:9=2.33 的常见横屏比例）。这也是为什么下面测试计划里专门留了 1080×1920 这个反例——不是随手挑的，是用来回归这个具体误判的。

**已知未解决的边界情况**：32:9 超宽显示器录屏（如 3840×1080）本身就是真实存在的平面视频分辨率，跟"横屏 16:9 素材做成 SBS"算出来的比例区间有重叠，这一版没有办法仅凭宽高比区分两者。这类内容较少见于 VR 播放器的典型素材，且检测错了可以通过资源库/HUD 里已有的手动修正入口纠正，接受这个已知限制。

### `FormatDetector.kt`

按上面"架构与数据流"重写 `detect()`，编排三层信号。

### `FormatSource.kt` / `Labels.kt` / 字符串资源

新增 `FormatSource.DETECTED_ASPECT_RATIO`，`Labels.kt` 的 `FormatSource.label()` 加一个分支，新增字符串资源 `format_source_aspect_ratio`（中文文案："宽高比推测"）。

## 非目标 / 后续可做

- Google Spherical Video V2 元数据解析（更准，但需要手动解析 MP4 UUID box，工作量大，先看这版够不够）。
- 360°+立体组合帧的专门识别。
- 视觉/内容层面的判断（比如真的解码几帧画面去看边缘是否有鱼眼镜头畸变）——这版只用容器里的宽高数字，不解码任何画面数据，保持轻量。

## 测试计划

`AspectRatioFormatDetector` 是纯函数，直接用真实机型分辨率当测试用例：

- 3840×1920、5760×2880 → SPHERE_360
- 2160×2160、1440×1440 → HEMISPHERE_180
- 3840×1080（半宽 1920:1080=16:9）→ SIDE_BY_SIDE
- 1920×2160（半高 1920:1080=16:9）→ TOP_AND_DOWN
- 1920×1080、1080×1920（常规 16:9/9:16 平面视频）→ null（不该命中任何一条）

`FormatDetector` 补几个组合测试：文件名只命中投影、宽高比补立体格式；文件名只命中立体格式、宽高比补投影；文件名两个都命中时宽高比完全不介入；文件名两个都没命中、宽高比两个都命中；宽高比也没命中时落到 `DEFAULT`。

不涉及真机验证——这层全是整数/浮点算术，JVM 单测就能完全覆盖，不需要模拟器或真机。
