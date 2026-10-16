package com.taiyeb.pocketguard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** [look] only picks the colours and motion of the ring behind the page's icon. */
private data class Page(@DrawableRes val icon: Int, val look: Phase, @StringRes val title: Int, @StringRes val body: Int)

private val pages = listOf(
    Page(R.drawable.ic_shield, Phase.IN_POCKET, R.string.onboarding_1_title, R.string.onboarding_1_body),
    Page(R.drawable.ic_power, Phase.WAITING_FOR_POCKET, R.string.onboarding_2_title, R.string.onboarding_2_body),
    Page(R.drawable.ic_bell, Phase.SIREN, R.string.onboarding_3_title, R.string.onboarding_3_body),
    Page(R.drawable.ic_science, Phase.IN_POCKET, R.string.onboarding_4_title, R.string.onboarding_4_body),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pagerState.currentPage == pages.lastIndex

    GlowBackground(pages[pagerState.currentPage].look.color) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip), color = Muted) }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Shrinks on short screens so the text always fits.
                    Box(
                        Modifier.weight(1f, fill = false).widthIn(max = 260.dp).aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        ParticleRing(page.look, Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(Brush.verticalGradient(listOf(page.look.color, page.look.color.copy(alpha = 0.6f))))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(page.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        stringResource(page.title),
                        color = TextPrimary,
                        fontSize = 27.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(page.body),
                        color = Muted,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                pages.indices.forEach { index ->
                    val selected = index == pagerState.currentPage
                    val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(if (selected) Purple else Track),
                    )
                }
            }
            PrimaryButton(
                stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next),
                onClick = {
                    if (last) onDone() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            )
        }
    }
}
