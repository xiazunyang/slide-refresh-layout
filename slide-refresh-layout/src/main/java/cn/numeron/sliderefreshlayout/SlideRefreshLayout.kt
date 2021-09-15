package cn.numeron.sliderefreshlayout

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Scroller
import androidx.core.view.NestedScrollingChild
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.ViewCompat
import cn.numeron.common.Direction
import kotlin.math.absoluteValue

class SlideRefreshLayout @JvmOverloads constructor(
    context: Context,
    attr: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attr, defStyle), NestedScrollingParent3 {

    /** 是否正在处理的状态 */
    private var isProcessing = false

    var message: CharSequence? = null

    var isRefresh: Boolean
        get() = isProcessing
        set(value) {
            isProcessing = value
            if (value) {
                processingType = Type.Refresh
                showSlidingView()
            } else {
                hideSlidingView()
            }
        }

    var isAppend: Boolean
        get() = isProcessing && processingType == Type.Append
        set(value) {
            isProcessing = value
            if (value) {
                processingType = Type.Append
                showSlidingView()
            } else {
                hideSlidingView()
            }
        }

    /** 开始滑动时受到的阻力 */
    var slidingResistivity = 1f

    /** 是否允许超出[SlidingView]的高度 */
    var canOverstep = true

    /** 当滑动到边界时，继续滑动将受到的阻力值 */
    var overstepResistivity = 0.4f

    var onAppendListener: OnAppendListener? = null
    var onRefreshListener: OnRefreshListener? = null

    private val slidingListeners = mutableSetOf<OnSlidingListener>()

    /** 真实边界 */
    private val bound = Rect()

    /** 用于记录当前布局的边界 */
    private val thisRect = Rect()

    /** 用于记录[processingSlidingView]的边界 */
    private val tempRect = Rect()

    /** 滚动器 */
    private val scroller = Scroller(context)

    /** 是否准备处理的状态 */
    private var beProcessing = false

    private var restoringSurplus = 0
    private var onTouching: Boolean = false

    /** 四个方向上的SlidingView */
    private var topSlidingView: View? = null
    private var leftSlidingView: View? = null
    private var rightSlidingView: View? = null
    private var bottomSlidingView: View? = null

    /** 正在处理的SlidingView和Type */
    private var processingType: Type? = null
    private var processingSlidingView: View? = null

    init {
        val typedArray =
            context.obtainStyledAttributes(attr, R.styleable.SlideRefreshLayout)
        canOverstep = typedArray.getBoolean(R.styleable.SlideRefreshLayout_overstep, canOverstep)
        slidingResistivity = typedArray.getFloat(
            R.styleable.SlideRefreshLayout_sliding_resistivity,
            slidingResistivity
        )
        overstepResistivity = typedArray.getFloat(
            R.styleable.SlideRefreshLayout_overstep_resistivity,
            overstepResistivity
        )
        typedArray.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

        val parentTop = paddingTop
        val parentLeft = paddingStart
        val parentRight = right - left - paddingEnd
        val parentBottom = bottom - top - paddingBottom

        repeat(childCount) { childIndex ->

            val childView = getChildAt(childIndex)

            if (childView.visibility != GONE) {

                val childLayoutParams = childView.layoutParams as LayoutParams

                val childWidth = childView.measuredWidth
                val childHeight = childView.measuredHeight

                val (layoutLeft, layoutTop) = when (childLayoutParams.direction) {
                    Direction.None -> {
                        val layoutTop = parentTop + childLayoutParams.topMargin
                        val layoutLeft = parentLeft + childLayoutParams.marginStart

                        bound.set(
                            bound.left.coerceAtMost(parentLeft),
                            bound.top.coerceAtMost(parentTop),
                            bound.right.coerceAtLeast(parentRight),
                            bound.bottom.coerceAtLeast(parentBottom),
                        )

                        layoutLeft to layoutTop
                    }
                    Direction.Left -> {
                        leftSlidingView = childView
                        val layoutLeft = -childWidth - childLayoutParams.marginEnd
                        val layoutTop = parentTop + childLayoutParams.topMargin
                        //修正左边界限
                        val boundLeft = layoutLeft - childLayoutParams.marginStart
                        bound.left = bound.left.coerceAtMost(boundLeft)
                        layoutLeft to layoutTop
                    }
                    Direction.Top -> {
                        topSlidingView = childView
                        val layoutLeft = parentLeft + childLayoutParams.marginStart
                        val layoutTop = -childHeight - childLayoutParams.bottomMargin
                        //修正顶部界限
                        val boundTop = layoutTop - childLayoutParams.topMargin
                        bound.top = bound.top.coerceAtMost(boundTop)
                        layoutLeft to layoutTop
                    }
                    Direction.Right -> {
                        rightSlidingView = childView
                        val layoutLeft = parentRight + childLayoutParams.marginStart
                        val layoutTop = parentTop + childLayoutParams.topMargin
                        //修正右边界限
                        val boundRight = layoutLeft + childWidth + childLayoutParams.marginEnd
                        bound.right = bound.right.coerceAtLeast(boundRight)
                        layoutLeft to layoutTop
                    }
                    Direction.Bottom -> {
                        bottomSlidingView = childView
                        val layoutLeft = parentLeft + childLayoutParams.marginStart
                        val layoutTop = parentBottom + childLayoutParams.topMargin
                        //修正底部界限
                        val boundBottom = layoutTop + childHeight + childLayoutParams.bottomMargin
                        bound.bottom = bound.bottom.coerceAtLeast(boundBottom)
                        layoutLeft to layoutTop
                    }
                }

                val layoutRight = layoutLeft + childWidth
                val layoutBottom = layoutTop + childHeight

                //为子View布局
                childView.layout(layoutLeft, layoutTop, layoutRight, layoutBottom)
            }
        }
    }

    /** 添加[OnSlidingListener] */
    fun addSlidingListener(slidingListener: OnSlidingListener) {
        slidingListeners.add(slidingListener)
    }

    /** 移除[OnSlidingListener] */
    fun removeSlidingListener(slidingListener: OnSlidingListener) {
        slidingListeners.remove(slidingListener)
    }

    fun restoreDelay(duration: Long, message: CharSequence? = null) {
        val slidingView = processingSlidingView
        if (slidingView is SlidingView) {
            if (!message.isNullOrEmpty()) {
                slidingView.setMessage(message)
            }
            val layoutParams = slidingView.layoutParams as LayoutParams
            val type = layoutParams.type
            if (type == Type.Refresh) {
                postDelayed({
                    isRefresh = false
                }, duration)
            } else if (type == Type.Append) {
                postDelayed({
                    isAppend = false
                }, duration)
            }
        }
    }

    private fun dispatchSliding(type: Type) {
        val slidingView = processingSlidingView
        val layoutParams = slidingView?.layoutParams as LayoutParams
        var threshold = 0
        var distance = 0
        when (layoutParams.direction) {
            Direction.Left, Direction.Right -> {
                threshold = tempRect.width()
                distance = scrollX.absoluteValue
            }
            Direction.Top, Direction.Bottom -> {
                threshold = tempRect.height()
                distance = scrollY.absoluteValue
            }
        }
        if (slidingView is SlidingView) {
            slidingView.onSliding(type, threshold, distance)
        }
        for (listener in slidingListeners) {
            listener.onSliding(type, threshold, distance)
        }
    }

    private fun dispatchRestoring(type: Type) {
        val slidingView = processingSlidingView
        var distance = 0

        val layoutParams = slidingView?.layoutParams as LayoutParams
        when (layoutParams.direction) {
            Direction.Left, Direction.Right -> {
                val dx = scrollX.absoluteValue
                if (restoringSurplus < dx) {
                    restoringSurplus = tempRect.width()
                }
                distance = restoringSurplus - dx
                restoringSurplus = dx
            }
            Direction.Top, Direction.Bottom -> {
                val dy = scrollY.absoluteValue
                if (restoringSurplus < dy) {
                    restoringSurplus = tempRect.height()
                }
                distance = restoringSurplus - dy
                restoringSurplus = dy
            }
        }

        if (slidingView is SlidingView) {
            slidingView.onRestoring(type, distance)
        }
        for (listener in slidingListeners) {
            listener.onRestoring(type, distance)
        }
    }

    private fun dispatchStart(type: Type) {
        val slidingView = processingSlidingView
        if (slidingView is SlidingView) {
            slidingView.onStartSliding(type)
        }
        for (listener in slidingListeners) {
            listener.onStartSliding(type)
        }
    }

    private fun dispatchFinish(type: Type) {
        val slidingView = processingSlidingView
        if (slidingView is SlidingView) {
            slidingView.onFinishSliding(type)
        }
        for (listener in slidingListeners) {
            listener.onFinishSliding(type)
        }
    }

    /** 只有当滚动的方向上有足够的空间，并且是触摸导致的滚动，就拦截滚动操作。 */
    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return !beProcessing &&
                target is NestedScrollingChild &&
                allowNestedScrollByAxes(axes) &&
                type == ViewCompat.TYPE_TOUCH
    }

    private fun allowNestedScrollByAxes(axes: Int): Boolean {
        return if (axes == ViewCompat.SCROLL_AXIS_VERTICAL) {
            bound.height() > height
        } else {
            bound.width() > width
        }
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        onTouching = type == ViewCompat.TYPE_TOUCH
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        if (processingSlidingView != null) {
            if (processingSlidingView == leftSlidingView && dx > 0 ||
                processingSlidingView == topSlidingView && dy > 0 ||
                processingSlidingView == rightSlidingView && dx < 0 ||
                processingSlidingView == bottomSlidingView && dy < 0
            ) {
                //在开始滑动后，当响应的SlidingView与操作方向相反，则拦截事件，并执行滚动
                val layoutParams = processingSlidingView?.layoutParams as LayoutParams
                val direction = layoutParams.direction
                var distanceX = dx
                var distanceY = dy
                //使滚动的距离不超过已偏移的距离
                when (direction) {
                    Direction.Left -> distanceX = dx.coerceAtMost(-scrollX)
                    Direction.Top -> distanceY = dy.coerceAtMost(-scrollY)
                    Direction.Right -> distanceX = dx.coerceAtLeast(-scrollX)
                    Direction.Bottom -> distanceY = dy.coerceAtLeast(-scrollY)
                    Direction.None -> Unit
                }
                //执行滚动，并记录已消耗的距离
                scrollBy(distanceX, distanceY)
                consumed[0] = distanceX
                consumed[1] = distanceY
                //在滚动后重新计算是否可以开始处理
                calculateBeProcessing()
            }
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        //计算滚动距离并滚动
        calculateScrollDistance(dxUnconsumed, dyUnconsumed)
        //消耗掉所有的距离
        consumed[0] = dxUnconsumed
        consumed[1] = dyUnconsumed
        //在滚动后重新计算是否可以开始处理
        calculateBeProcessing()
    }

    /** 计算滚动距离，并执行滚动操作 */
    private fun calculateScrollDistance(dxUnconsumed: Int, dyUnconsumed: Int) {
        var distanceX = dxUnconsumed
        var distanceY = dyUnconsumed

        if (!canOverstep) {
            //如果不能超出界限，则限制其上下限
            distanceX = distanceX.coerceIn(bound.left - scrollX, bound.right - scrollX - width)
            distanceY = distanceY.coerceIn(bound.top - scrollY, bound.bottom - scrollY - height)
        }

        if (slidingResistivity != 1f) {
            //添加滑动阻力值
            distanceX = (distanceX * slidingResistivity).toInt()
            distanceY = (distanceY * slidingResistivity).toInt()
        }

        if (canOverstep) {
            //如果允许在超出SlidingView的边界后继续滑动，则在超出后添加一个阻力
            if (scrollX < bound.left || scrollX + width > bound.right) {
                distanceX = (dxUnconsumed * overstepResistivity).toInt()
            }
            if (scrollY < bound.top || scrollY + height > bound.bottom) {
                distanceY = (dyUnconsumed * overstepResistivity).toInt()
            }
        }

        scrollBy(distanceX, distanceY)

        //计算滚动时需要操作的SlidingView
        when {
            dyUnconsumed > 0 -> processingSlidingView = bottomSlidingView
            dyUnconsumed < 0 -> processingSlidingView = topSlidingView
            dxUnconsumed > 0 -> processingSlidingView = rightSlidingView
            dxUnconsumed < 0 -> processingSlidingView = leftSlidingView
            else -> Unit
        }
    }

    /**
     * 当主动滚动到了阈值之内，则标记为可刷新，即可在松开手指时开始刷新
     * 但是，如果在松开手指之前，如果又恢复到了阈值之外，则取消标记
     */
    private fun calculateBeProcessing() {
        beProcessing = ensureProcessingType() != null && contains(processingSlidingView!!)
        val processingType = processingType
        if (processingType != null) {
            //处理回调
            dispatchSliding(processingType)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val processingSlidingView = processingSlidingView
        //处理阻力
        if (processingSlidingView != null) {
            if (scrollX < bound.left) {
                processingSlidingView.translationX = (scrollX - bound.left) * 0.5f
            }
            if (scrollY < bound.top) {
                processingSlidingView.translationY = (scrollY - bound.top) * 0.5f
            }
            if (scrollX + width > bound.right) {
                processingSlidingView.translationX = (scrollX - processingSlidingView.width) * 0.5f
            }
            if (scrollY + height > bound.bottom) {
                processingSlidingView.translationY = (scrollY - processingSlidingView.height) * 0.5f
            }
        }

        if (!onTouching && beProcessing && !isProcessing) {
            //当因非触摸导致了恢复性质的滚动时，处理onRestoring回调
            dispatchRestoring(processingType!!)
        }

        //当滚动状态复位时，则置空processingSlidingView
        if (scrollX == 0 && scrollY == 0) {
            processingType?.let(::dispatchFinish)
            processingSlidingView?.translationX = 0f
            processingSlidingView?.translationY = 0f
            this.tempRect.setEmpty()
            this.beProcessing = false
            this.processingType = null
            this.processingSlidingView = null
        }

    }

    private var contains = false

    private operator fun contains(view: View): Boolean {
        tempRect.set(view.left, view.top, view.right, view.bottom)
        thisRect.set(scrollX, scrollY, scrollX + width, scrollY + height)

        val layoutParams = view.layoutParams as LayoutParams
        val slidingThreshold = layoutParams.slidingThreshold
        when (layoutParams.direction) {
            Direction.Left -> tempRect.left = (tempRect.left * slidingThreshold).toInt()
            Direction.Top -> tempRect.top = (tempRect.top * slidingThreshold).toInt()
            Direction.Right -> tempRect.right = (tempRect.right * slidingThreshold).toInt()
            Direction.Bottom -> tempRect.bottom = (tempRect.bottom * slidingThreshold).toInt()
            Direction.None -> Unit
        }
        val contains = thisRect.contains(tempRect)
        if (this.contains != contains) {
            this.contains = contains
        }
        return contains
    }

    private fun ensureProcessingType(): Type? {
        val view = processingSlidingView ?: return null
        val layoutParams = view.layoutParams as LayoutParams
        if (processingType != layoutParams.type) {
            processingType = layoutParams.type
        }
        return processingType
    }

    /** 根据操作的[SlidingView]决定执行哪个回调 */
    private fun executeListener() {
        //标记为正在处理
        isProcessing = true
        //展示SlidingView
        showSlidingView()
        if (processingType == Type.Refresh) {
            onRefreshListener?.onRefresh()
        } else if (processingType == Type.Append) {
            onAppendListener?.onAppend()
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) = Unit

    override fun onStopNestedScroll(target: View, type: Int) {
        onTouching = false
        if (type == ViewCompat.TYPE_TOUCH) {
            if (beProcessing && processingSlidingView != null) {
                executeListener()
            } else if (scrollX != 0 || scrollY != 0) {
                hideSlidingView()
            }
        }
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        return processingSlidingView != null
    }

    private fun findViewByType(type: Type): View {
        repeat(childCount) {
            val childView = getChildAt(it)
            val layoutParams = childView.layoutParams as LayoutParams
            if (layoutParams.type == type) {
                return childView
            }
        }
        throw IllegalStateException("No type view was found: $type.")
    }

    private fun showSlidingView() {
        //处理回调
        dispatchStart(processingType!!)
        //找到需要处理的View
        val processingView = findViewByType(processingType!!)
        val layoutParams = processingView.layoutParams as LayoutParams
        //滚动到该位置
        scrollTo(layoutParams.direction)
    }

    private fun hideSlidingView() {
        scrollTo(Direction.None)
    }

    private fun scrollTo(direction: Direction) {
        val (dx, dy) = when (direction) {
            Direction.None -> -scrollX to -scrollY
            Direction.Top -> -scrollX to bound.top - scrollY
            Direction.Left -> bound.left - scrollX to -scrollY
            Direction.Right -> bound.right - scrollX - width to -scrollY
            Direction.Bottom -> -scrollX to bound.bottom - scrollY - height
        }
        scroller.startScroll(scrollX, scrollY, dx, dy, 300)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            invalidate()
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean {
        return p is LayoutParams
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams {
        return LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams {
        return when (lp) {
            is MarginLayoutParams -> LayoutParams(lp)
            is ViewGroup.LayoutParams -> LayoutParams(lp)
            else -> generateDefaultLayoutParams()
        }
    }

    inner class LayoutParams : MarginLayoutParams {

        var type: Type? = null
        var direction = Direction.None
        var slidingThreshold: Float = 1f

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs) {
            val typedArray = c.obtainStyledAttributes(attrs, R.styleable.SlideRefreshLayout_Layout)
            slidingThreshold = typedArray.getFloat(
                R.styleable.SlideRefreshLayout_Layout_layout_slide_threshold,
                slidingThreshold
            )
            val directionValue =
                typedArray.getInt(R.styleable.SlideRefreshLayout_Layout_layout_direction, -1)
            if (directionValue != -1) {
                direction = Direction.values()[directionValue]
            }
            val typeValue =
                typedArray.getInt(R.styleable.SlideRefreshLayout_Layout_layout_slide_type, -1)
            if (typeValue != -1) {
                type = Type.values()[typeValue]
            }
            typedArray.recycle()
        }

        @JvmOverloads
        constructor(
            width: Int,
            height: Int,
            direction: Direction = Direction.None,
            type: Type = Type.Refresh
        ) : super(width, height) {
            this.type = type
            this.direction = direction
        }

        constructor(source: ViewGroup.LayoutParams) : super(source)
        constructor(source: MarginLayoutParams) : super(source)
    }

    fun interface OnRefreshListener {
        fun onRefresh()
    }

    fun interface OnAppendListener {
        fun onAppend()
    }

    interface OnSlidingListener {

        /**
         * 当被主动滑动时调用，会调用多次
         * @param type [Type] 因滑动操作需要响应的类型，[Type.Refresh]或者[Type.Append]
         * @param threshold [Int] 响应距离的阈值
         * @param distance [Int] 总共滑动了多少距离
         */
        fun onSliding(type: Type, threshold: Int, distance: Int)

        /**
         * 在复位过程中调用，会调用多次
         * @param type [Type] 因滑动操作需要响应的类型，[Type.Refresh]或者[Type.Append]
         * @param distance [Int] 移动了多少距离
         */
        fun onRestoring(type: Type, distance: Int)

        /**
         * 在刷新或追加的操作开始时调用
         * @param type [Type] 因滑动操作需要响应的类型，[Type.Refresh]或者[Type.Append]
         */
        fun onStartSliding(type: Type)

        /**
         * 在刷新或追加的操作停止时调用
         * @param type [Type] 因滑动操作需要响应的类型，[Type.Refresh]或者[Type.Append]
         */
        fun onFinishSliding(type: Type)

    }

}