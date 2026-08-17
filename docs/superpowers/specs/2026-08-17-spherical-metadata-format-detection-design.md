# 用视频文件自带的球面/立体元数据做格式检测（替换文件名 + 宽高比两层）

## 背景

现有的 `FormatDetector` 是三层启发式流水线：容器多视图探测（`MultiviewTrackProbe`，检测 MV-HEVC 多轨）→
文件名关键词（`FilenameFormatDetector`）→ 宽高比推测（`AspectRatioFormatDetector`）。用 `ffprobe` 实测
`/Users/zohar/Downloads/视频/` 下三个真实视频文件（用户已经把文件名改成标注真实格式：`180-LR.mp4`、
`360_TB.mp4`、`360.mp4`）发现：

1. 这三个文件全部携带**真实的、标准的嵌入元数据**——MP4 容器里的 `st3d`（Stereoscopic 3D Video Box，
   ISO/IEC 23001-10）和 `sv3d`（Spherical Video V2 Box，Google 的公开规范）——这类 box 是 Insta360/GoPro
   这类 360 相机导出视频的标准写法，`ffprobe` 的 MP4 解封装器能读出来（表现为 `side_data_list` 里的
   `"Stereo 3D"`/`"Spherical Mapping"` 条目），系统播放器能正确识别格式，大概率就是读的这个。
2. 拿这三个文件真的跑一遍现有的三层检测代码：`180-LR.mp4` 被误判成 `SPHERE_360/MONO`（应为
   `HEMISPHERE_180/SIDE_BY_SIDE`），`360_TB.mp4` 被误判成 `HEMISPHERE_180/TOP_AND_DOWN`（应为
   `SPHERE_360/TOP_AND_DOWN`，立体格式对但投影错）。根因不是阈值没调好：文件名关键词要求数字两边都带
   下划线（`_180_`/`_360_`），这三个真实文件名一个都不满足；宽高比检测的球体/半球分支永远只返回 MONO，
   而 180°SBS 整帧宽高比和 360°单目完全相同（都是 2:1，因为 SBS 每只眼睛都是正方形半球画面），
   360°TB 和 180°单目也完全相同（都是 1:1），这是几何结构决定的，不是能调阈值解决的问题。

用户决定：**彻底删除文件名检测和宽高比兜底这两层**，改成直接解析视频文件本身的 `st3d`/`sv3d` box。

## 设计

### 检测流水线（新）

```
容器多视图探测（MultiviewTrackProbe，不变，检测 MV-HEVC 多轨，跟 st3d/sv3d 是两套不相关的机制）
  ↓ 命中 isMultiview → stereoMode 固定为 MULTIVIEW_MVHEVC，projection 仍然走下面这层补
  ↓ 未命中
球面/立体元数据探测（新：SphericalStereoMetadataProbe，解析 st3d + sv3d）
  ↓
默认 FLAT/MONO（FormatSource.DEFAULT，机制不变，用户在 HUD/底部栏手动改）
```

`FormatDetector.detect()` 的签名去掉 `displayName: String` 参数——文件名不再被任何检测逻辑消费。

### 新组件一：`Mp4BoxReader`（纯 Kotlin，通用 box 树遍历）

按 ISO/IEC 14496-12 的 box 结构解析：每个 box 是 4 字节大端 size + 4 字节 FourCC type（size 字段为 1
时，后面紧跟 8 字节 largesize；size 为 0 表示这个 box 一直延伸到文件末尾，这次不需要处理这种情况，
`st3d`/`sv3d`/它们的所有祖先 box 都不会用 size=0）。

```kotlin
package tech.illusion.spaceplayer.library.boxparse

/** A random-access source of bytes - one impl wraps a real file's FileChannel (production), another
 * wraps a plain ByteArray (unit tests, no file I/O). */
interface SeekableByteSource {
    val size: Long
    /** Reads exactly [length] bytes starting at [position]. Throws if fewer bytes are available. */
    fun readAt(position: Long, length: Int): ByteArray
}

data class BoxHeader(
    val type: String,
    /** Absolute offset of this box's first byte (the size field), for computing the next sibling's offset. */
    val start: Long,
    /** Total box size including its own header, per the size/largesize field. */
    val totalSize: Long,
    /** Absolute offset where this box's payload (children, for a container box) begins. */
    val payloadStart: Long,
) {
    val end: Long get() = start + totalSize
}

object Mp4BoxReader {
    /** Reads one box header at [offset]. Returns null if fewer than 8 bytes remain before [rangeEnd]. */
    fun readHeaderAt(source: SeekableByteSource, offset: Long, rangeEnd: Long): BoxHeader?

    /** Scans direct children of a container box occupying [rangeStart] until [rangeEnd] (both absolute
     * offsets), returns the first child whose type matches, or null. Does not recurse. */
    fun findChild(source: SeekableByteSource, rangeStart: Long, rangeEnd: Long, type: String): BoxHeader?

    /** Walks a path of box types from the file root (e.g. ["moov", "trak", "mdia", "minf", "stbl", "stsd"]),
     * descending into each container box's payload range to find the next path segment. Returns null as
     * soon as any segment is missing. Every segment before the last must be a container box (its payload
     * IS the byte range to search for the next segment) - this holds for the whole moov/trak/mdia/minf/stbl
     * chain used here. */
    fun findPath(source: SeekableByteSource, path: List<String>): BoxHeader?
}
```

`findPath`/`findChild` 在遇到损坏/截断的 box（比如剩余字节不够 8 字节读 size+type，或者 size 字段指向
超出 `rangeEnd` 的位置）时返回 `null`，不抛异常——探测失败就是"没找到这个信息"，不是错误。

### 新组件二：`SphericalStereoMetadataProbe`

```kotlin
package tech.illusion.spaceplayer.library

data class SphericalMetadataHint(val projection: Projection?, val stereoMode: StereoMode?)

interface SphericalMetadataProbe {
    fun probe(context: Context, uri: Uri): SphericalMetadataHint
}

class Mp4SphericalStereoMetadataProbe : SphericalMetadataProbe {
    override fun probe(context: Context, uri: Uri): SphericalMetadataHint {
        // 1. 用 context.contentResolver.openFileDescriptor(uri, "r") 拿 ParcelFileDescriptor，
        //    包成 FileInputStream(pfd.fileDescriptor).channel 作为 FileChannel——这是
        //    PlaybackManager.setup() 已经在用的同一个"content:// Uri 背后是真实文件"的假设，跟
        //    MultiviewTrackProbe 用 MediaExtractor 读同一个 Uri 是并列的、独立的一次文件打开。
        //    用 FileChannel 而不是 InputStream 顺序读，是因为 moov box 在文件里的位置不固定（有的
        //    视频前置"faststart"、有的在文件末尾），随机 seek 直接跳到目标 box 不需要读过中间几百 MB
        //    的 mdat 视频数据。
        // 2. 找 moov 下第一条 mdia/hdlr 的 handler_type 是 "vide" 的 trak（hdlr box payload：
        //    4 字节版本+flags，4 字节 pre_defined，4 字节 handler_type FourCC，跟 MultiviewTrackProbe
        //    "拿第一条 video/ mime 轨道"是同一个"只认第一条视频轨"的原则，保持一致）。
        // 3. 沿 mdia/minf/stbl/stsd 往下找，stsd box 的 payload 开头是 4 字节版本+flags、4 字节
        //    entry_count，第一个 sample entry（比如 hvc1/hev1/avc1）紧跟其后，本身也是一个 box。
        // 4. sample entry 的 payload 开头是固定 78 字节的 VisualSampleEntry 结构体（reserved/
        //    data_reference_index/pre_defined/width/height/...），不是 box，跳过这 78 字节之后剩下
        //    的部分才是子 box 序列（编解码配置 box 比如 hvcC，以及可能存在的 st3d/sv3d）。
        // 5. 在这段子 box 序列里分别找 "st3d" 和 "sv3d"（用 findChild，不需要 findPath，因为已经在
        //    目标范围内）：
        //    - st3d 存在：payload 是 4 字节版本+flags + 1 字节 stereo_mode（0=单目/1=上下/2=左右）—
        //      映射到 StereoMode.MONO/TOP_AND_DOWN/SIDE_BY_SIDE。stereo_mode=3（stereo-custom）当作
        //      未识别（null）处理，这次不支持。
        //    - sv3d 存在：不需要再往下解析 proj/prji/equi 拿具体参数——这次验证过的两个真实 360 文件
        //      都是"sv3d 存在就是 equirect 360"，跟 sv3d 规范本身就是为完整球面视频设计的这一事实一致；
        //      直接映射成 Projection.SPHERE_360。
        //    - sv3d 不存在：projection 为 null（不是 FLAT，是"没检测到"——跟 FormatDetector 里
        //      filenameHint 的"null 表示没提到"是同一个约定，DetectedFormat 兜底成 FLAT 在
        //      FormatDetector 里统一做，不在这里做）。
        //    - 180° 半球目前没有对应的 box 信号（这次验证过的真实 180°SBS 文件只有 st3d 没有
        //      sv3d）——这是已知限制，见下面"已知限制"一节，不是这里要处理的 bug。
        // 6. 任何一步没找到（trak 没有、stsd 没有、sample entry 没有等），返回
        //    SphericalMetadataHint(null, null)，不抛异常。
    }
}
```

### `FormatDetector` 改动

```kotlin
class FormatDetector(
    private val multiviewTrackProbe: MultiviewTrackProbe,
    private val sphericalMetadataProbe: SphericalMetadataProbe,
) {
    fun detect(context: Context, uri: Uri): DetectedFormat {
        val containerResult = multiviewTrackProbe.probe(context, uri)
        val metadataHint = sphericalMetadataProbe.probe(context, uri)

        if (containerResult.isMultiview) {
            return DetectedFormat(
                projection = metadataHint.projection ?: Projection.FLAT,
                stereoMode = StereoMode.MULTIVIEW_MVHEVC,
                formatSource = FormatSource.DETECTED_CONTAINER,
            )
        }

        val formatSource = if (metadataHint.projection != null || metadataHint.stereoMode != null) {
            FormatSource.DETECTED_METADATA
        } else {
            FormatSource.DEFAULT
        }
        return DetectedFormat(
            projection = metadataHint.projection ?: Projection.FLAT,
            stereoMode = metadataHint.stereoMode ?: StereoMode.MONO,
            formatSource = formatSource,
        )
    }
}
```

`LibraryViewModel`：`FormatDetector(MediaExtractorMultiviewProbe())` 改成
`FormatDetector(MediaExtractorMultiviewProbe(), Mp4SphericalStereoMetadataProbe())`；
`formatDetector.detect(context, record.uri, record.displayName)` 去掉最后一个参数。

### 清理：`ContainerProbeResult` 瘦身

`videoWidth`/`videoHeight` 这两个字段唯一的消费者就是即将删除的宽高比兜底逻辑，删掉后
`ContainerProbeResult` 简化成 `data class ContainerProbeResult(val isMultiview: Boolean)`，
`MediaExtractorMultiviewProbe.probe()` 里跟踪"第一条视频轨宽高"的那部分逻辑（`firstWidth`等局部变量
之外，专门为宽高比检测加的 `videoWidth`/`videoHeight` 局部变量和赋值）一并删掉，只保留 HEVC 轨道计数
判断多视图的部分。

### `FormatSource` 枚举 + 展示文案

`VideoItem.kt`：`enum class FormatSource { DETECTED_CONTAINER, DETECTED_FILENAME, DETECTED_ASPECT_RATIO, MANUAL_OVERRIDE, DEFAULT }`
改成 `{ DETECTED_CONTAINER, DETECTED_METADATA, MANUAL_OVERRIDE, DEFAULT }`。

`ui/Labels.kt` 的 `FormatSource.label()` 对应删掉 `DETECTED_FILENAME`/`DETECTED_ASPECT_RATIO` 两个分支，
加一个 `DETECTED_METADATA -> R.string.format_source_metadata`。

`res/values/strings.xml` 删除 `format_source_filename`/`format_source_aspect_ratio`，新增
`format_source_metadata`（英文取 "Metadata detection"，说明是从视频文件本身读到的，不是猜的）。
`res/values-zh/strings.xml` 同步删除对应两条，新增中文覆盖（"元数据识别"）。

### 删除的文件

- `library/FilenameFormatDetector.kt` + `test/library/FilenameFormatDetectorTest.kt`
- `library/AspectRatioFormatDetector.kt` + `test/library/AspectRatioFormatDetectorTest.kt`

`FakeMultiviewTrackProbe`（测试用）的 `videoWidth`/`videoHeight` 构造参数一并删掉，只保留
`isMultiview: Boolean`。新增 `FakeSphericalMetadataProbe`（同一个 `fakes` 包），构造参数直接是
`SphericalMetadataHint`。

### UI：网格卡片去掉彩色格式徽标

`VideoGridCard.kt` 里缩略图左上角那个显示 `"${projection.label()} · ${stereoLabel}"` 的彩色 `Badge`
整块删除。右下角的时长徽标、缩略图下方 `"${item.formatSource.label()} · ${formatFileSize(...)}"`
这行文字都保留不动。

### 已知限制（如实记录，这次不解决）

1. **旧的 Google Spatial Media V1 元数据方案（`uuid` box 里嵌 XML 文本）明确不做**——这次验证过的三个
   真实文件用的都是新方案（`st3d`/`sv3d`），支持哪套元数据方案跟这次的问题无关，旧方案作为已知的后续
   扩展点记录，不在这次范围内。
2. **只有 `st3d` 没有 `sv3d` 的文件（比如这次的 `180-LR.mp4`）拿不到 180 这个信息**——立体格式（SBS）
   能正确识别，投影方式因为没有对应的 box 信号会落到默认 FLAT，需要用户手动改成 180。这是真实数据决定
   的（`sv3d` 规范本身就是为完整球面视频设计的，180° 半球目前没有标准化的 box 信号），不是这次实现的
   疏漏，也不打算用宽高比之类的启发式去猜——这正是这次要删除宽高比兜底的原因。

## 测试

- **纯 JVM 单测**（`Mp4BoxReaderTest`）：手写最小的合成字节序列验证 box 遍历本身——嵌套 box 找路径、
  box 缺失返回 null、size 字段用 largesize（64 位）的 box、box 尾部被截断（剩余字节不够声明的 size）
  时不抛异常、`findChild` 在有多个同级 box 时能跳过不匹配的找到匹配的。
- **纯 JVM 单测**（`Mp4SphericalStereoMetadataProbeTest` 或直接在同一个测试类里）：基于
  `Mp4BoxReader` 之上，构造几组最小的合成 moov/trak/mdia/minf/stbl/stsd/hvc1[78 字节头 +
  st3d/sv3d] 字节树，覆盖：st3d+sv3d 都在（对应 `360_TB.mp4` 的情况）、只有 st3d（对应
  `180-LR.mp4`）、只有 sv3d（对应 `360.mp4`）、都没有、`stsd` 或更上层缺失。全部走
  `SeekableByteSource` 的 `ByteArray` 实现，不需要真实视频文件做单测夹具。
- **`FormatDetectorTest` 重写**：现有 8 个用例全部依赖文件名字符串和宽高比数字，需要换成基于
  `FakeSphericalMetadataProbe` 构造不同的 `SphericalMetadataHint` 组合（模拟 st3d+sv3d 都命中/只命中
  一个/都不命中），跟 `FakeMultiviewTrackProbe(isMultiview = true/false)` 交叉，覆盖跟现在等价的
  分支覆盖度（容器命中+有/无元数据、容器未命中+元数据两个字段都有/只有一个/都没有）。
- **真机验证**（不是单测，是这次功能改动本身的验收）：把 `/Users/zohar/Downloads/视频/` 下三个真实
  文件推到模拟器或真机的 `MediaStore`，走一遍资源库刷新，截图确认格式徽标从卡片上消失、
  底部栏/HUD 手动修正入口显示的当前格式符合"已知限制"一节里写的预期（`360_TB.mp4`/`360.mp4` 全对，
  `180-LR.mp4` 立体格式对、投影是默认 FLAT 需要手动改）。这三个文件体积较大（最大 700MB），不提交进
  仓库，只用于本地一次性验证。
