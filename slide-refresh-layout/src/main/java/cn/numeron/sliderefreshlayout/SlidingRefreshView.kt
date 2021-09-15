package cn.numeron.sliderefreshlayout

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class SlidingRefreshView @JvmOverloads constructor(
    c: Context,
    a: AttributeSet? = null,
    i: Int = R.attr.slidingRefreshViewStyle,
    s: Int = R.style.SlidingRefreshViewStyle
) : FrameLayout(c, a, i, s), SlidingView {

    private var slidingText = c.getText(R.string.sliding_refresh_text)
    private var thresholdText = c.getText(R.string.sliding_refresh_text_threshold)
    private var refreshingText = c.getText(R.string.sliding_refresh_text_refreshing)
    private val textView: TextView
    private val imageView: ImageView

    private val loadingAnimation = RotateAnimation(
        0f,
        360f,
        Animation.RELATIVE_TO_SELF,
        0.5f,
        Animation.RELATIVE_TO_SELF,
        0.5f
    )

    init {
        View.inflate(c, R.layout.sliding_loading_view, this)
        textView = findViewById(R.id.sliding_loading_text_view)
        imageView = findViewById(R.id.sliding_loading_image_view)

        val typedArray = c.obtainStyledAttributes(a, R.styleable.SlidingRefreshView, i, s)
        val drawableId =
            typedArray.getResourceId(R.styleable.SlidingRefreshView_sliding_refresh_image, -1)
        if (drawableId != -1) {
            imageView.setImageResource(drawableId)
        }
        val slidingText = typedArray.getString(R.styleable.SlidingRefreshView_sliding_refresh_text)
        if (slidingText != null) {
            this.slidingText = slidingText
        }
        val thresholdText =
            typedArray.getString(R.styleable.SlidingRefreshView_sliding_refresh_text_threshold)
        if (thresholdText != null) {
            this.thresholdText = thresholdText
        }
        val refreshingText =
            typedArray.getString(R.styleable.SlidingRefreshView_sliding_refresh_text_refreshing)
        if (refreshingText != null) {
            this.refreshingText = refreshingText
        }
        typedArray.recycle()

        loadingAnimation.duration = 1000
        loadingAnimation.repeatMode = ValueAnimator.RESTART
        loadingAnimation.repeatCount = ValueAnimator.INFINITE
        loadingAnimation.interpolator = LinearInterpolator()
    }

    override fun onSliding(type: Type, threshold: Int, distance: Int) {
        val text = if (distance < threshold) slidingText else thresholdText
        if (textView.text != text) {
            textView.text = text
        }
    }

    override fun onRestoring(type: Type, distance: Int) {
        if (loadingAnimation.hasStarted()) {
            imageView.clearAnimation()
        }
    }

    override fun onStartSliding(type: Type) {
        imageView.startAnimation(loadingAnimation)
        textView.text = refreshingText
    }

    override fun onFinishSliding(type: Type) {

    }

    override fun setMessage(message: CharSequence) {
        textView.text = message
    }

    companion object {

        private const val TAG = "SlidingRefreshView"

    }

}