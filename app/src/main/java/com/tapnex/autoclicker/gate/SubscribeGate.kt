package com.tapnex.autoclicker.gate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tapnex.autoclicker.R
import kotlinx.coroutines.delay

const val YOUTUBE_URL = "https://youtube.com/@nexo4444-1?si=SXH2zoILeqhELN4D"
const val TIKTOK_URL = "https://www.tiktok.com/@aloo.pc?_r=1&_t=ZN-9BHXoU4OIjK"

private val YOUTUBE_APPS = listOf("com.google.android.youtube")
private val TIKTOK_APPS = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

private val GateBg = Color(0xFF0B0F12)
private val GateCard = Color(0xFF161D22)
private val GateRed = Color(0xFFE53935)
private val GateYellow = Color(0xFFF5B700)
private val GateGreen = Color(0xFF1FD39A)
private val GateWhite = Color(0xFFFFFFFF)
private val GateMuted = Color(0xFF8E9AA3)

/** Opens the link in its app (YouTube / TikTok). Falls back to any app that handles the link. Never opens inside TAPNEX. */
fun openExternal(ctx: Context, url: String, preferredPackages: List<String>) {
    for (pkg in preferredPackages) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        try {
            ctx.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // App not installed, try the next one.
        }
    }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No app can open the link.
    }
}

/**
 * Shown after the splash on every launch. No cancel or skip: the user must open both links
 * and confirm both follows before the app continues.
 */
@Composable
fun SubscribeGateScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var ytOpened by rememberSaveable { mutableStateOf(false) }
    var ytDone by rememberSaveable { mutableStateOf(false) }
    var ttOpened by rememberSaveable { mutableStateOf(false) }
    var ttDone by rememberSaveable { mutableStateOf(false) }
    val allDone = ytDone && ttDone

    LaunchedEffect(allDone) {
        if (allDone) {
            delay(700)
            onDone()
        }
    }

    Box(
        Modifier.fillMaxSize().background(GateBg).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Subscribe & Follow to unlock",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = GateWhite,
                textAlign = TextAlign.Center
            )
            Text(
                when {
                    allDone -> "All steps complete. Opening TAPNEX..."
                    ytDone -> "1 step complete"
                    else -> "Complete both steps to continue"
                },
                color = GateYellow,
                fontWeight = FontWeight.Bold
            )

            StepCard(
                title = "Subscribe on YouTube",
                iconRes = R.drawable.ic_youtube,
                enabled = true,
                opened = ytOpened,
                done = ytDone,
                openLabel = "Subscribe",
                confirmLabel = "I've subscribed",
                onOpen = {
                    openExternal(ctx, YOUTUBE_URL, YOUTUBE_APPS)
                    ytOpened = true
                },
                onConfirm = { ytDone = true }
            )

            StepCard(
                title = "Follow on TikTok",
                iconRes = R.drawable.ic_tiktok,
                enabled = ytDone,
                opened = ttOpened,
                done = ttDone,
                openLabel = "Follow",
                confirmLabel = "I've followed",
                onOpen = {
                    openExternal(ctx, TIKTOK_URL, TIKTOK_APPS)
                    ttOpened = true
                },
                onConfirm = { ttDone = true }
            )
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    iconRes: Int,
    enabled: Boolean,
    opened: Boolean,
    done: Boolean,
    openLabel: String,
    confirmLabel: String,
    onOpen: () -> Unit,
    onConfirm: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = GateCard),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = GateWhite,
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(visible = done, enter = fadeIn() + scaleIn()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Done",
                        tint = GateGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Completed", color = GateGreen, fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(visible = !done) {
                if (!opened) {
                    Button(
                        onClick = onOpen,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GateRed, contentColor = GateWhite)
                    ) {
                        Text(openLabel, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onConfirm,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GateYellow, contentColor = Color.Black)
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
