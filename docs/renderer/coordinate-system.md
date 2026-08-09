# 坐标系统

## 1. 双坐标系

| 坐标 | 单位 | 用途 |
|------|------|------|
| 相对坐标 `RelativePoint(x, y)` | `StaffSpace` | 布局、音乐逻辑 |
| 绝对坐标 `AbsolutePoint(x, y)` | `Pixels` | 屏幕渲染 |

`StaffSpace` 是与字号无关的音乐度量——**1 staff space = 谱线间距**。
`Pixels` 是屏幕像素（已乘以 density）。

经验值：
- 五线谱总高度 ≈ 4 staff spaces
- 符头宽 ≈ 1.3 staff spaces
- 符杆默认长 ≈ 3.5 staff spaces

## 2. CoordinateTransformer

```kotlin
class CoordinateTransformer {
    fun setScale(scale: ScaleFactor)
    fun setViewportBounds(bounds: AbsoluteRect)

    fun toAbsolute(point: RelativePoint): AbsolutePoint
    fun toAbsolute(rect:  RelativeRect):  AbsoluteRect
    fun toPixels(staffSpaces: StaffSpace): Pixels

    fun toRelative(point: AbsolutePoint): RelativePoint
    fun toStaffSpaces(pixels: Pixels):    StaffSpace
}
```

`scale` 决定 1 staff space 对应多少像素；缩放与平移在这里集中处理，渲染管线下游只看相对坐标。

## 3. SMuFL 坐标 Y 轴翻转

SMuFL 字形坐标 **Y 向上**，渲染坐标 **Y 向下**：

```
SMuFL                    Render
   ↑ Y                    (0,0)
   │  ┌──┐                  └─→ X
   │  │● │                  │  ┌──┐
   │  └──┘                  │  │● │
   └──→ X                   ↓  └──┘
   (0,0)                       Y
```

`RenderHelpers.createGlyphCommand` 中的转换：

```kotlin
val scale = fontSize / 4f          // 1 em = 4 staff spaces
val bounds = AbsoluteRect(
    origin = AbsolutePoint(
        origin.x + scale * bbox.southWest.x,    // X 直接缩放
        origin.y - scale * bbox.northEast.y,    // Y 翻转：减号
    ),
    width  = scale * bbox.width,
    height = scale * bbox.height,
)
```

## 4. 各类元素的基点语义

| 元素 | 基点（绘制时给定的 origin） |
|------|----------------------------|
| Notehead | 左边缘 + 谱线 Y |
| Rest | SMuFL origin |
| Clef / Accidental / TimeSignature | SMuFL origin（位于谱线相应位置） |
| Flag | 符杆顶端 |

> 字形 BBox / advance / anchor 由 `BravuraFont.context(...)` 提供。

## 5. 拾取的坐标变换链

```
AbsolutePoint (像素)
   │  CoordinateTransformer.toRelative
   ▼
RelativePoint (StaffSpace, 全局)
   │  二分查找 SystemNode → BPlusTree(measure prefix sum)
   ▼
单元格相对坐标 (X 相对小节起点, Y 相对谱表中线)
   │  线性扫描 MeasureStaffCell
   ▼
ScoreHittableElement
```

详见 [spatial-index.md](spatial-index.md)。
