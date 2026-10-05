package eu.kanade.tachiyomi.mslime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SlimeIntroActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlimeIntro {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun SlimeIntro(onDone: () -> Unit) {
    val drop = remember { Animatable(-600f) }
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        drop.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
        coroutineScope {
            launch {
                sx.animateTo(1.4f, tween(110))
                sx.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            launch {
                sy.animateTo(0.6f, tween(110))
                sy.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            launch { fade.animateTo(1f, tween(500)) }
        }
        delay(300)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F13)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_mihon),
                contentDescription = null,
                modifier = Modifier.size(160.dp).graphicsLayer {
                    translationY = drop.value
                    scaleX = sx.value
                    scaleY = sy.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Manhwa Slime",
                color = Color(0xFF2DB8FD),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
    }
}
