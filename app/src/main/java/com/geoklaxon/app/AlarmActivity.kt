package com.geoklaxon.app

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AlarmActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true);setTurnScreenOn(true)
        val name=intent.getStringExtra("alarm_name") ?: "YOUR ALARM"
        val token=intent.getStringExtra("alarm_token") ?: ""
        setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF160725)).padding(28.dp),
                horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.SpaceEvenly) {
                Text("⚡ GEOKLAXON! ⚡",fontSize=38.sp,fontWeight=FontWeight.Black,color=Color(0xFFFFEB00))
                Image(painter=painterResource(R.drawable.mascot_boombox),
                    contentDescription="Boombox mascot",modifier=Modifier.size(width=220.dp,height=160.dp))
                Text("WAKE UP!",fontSize=54.sp,fontWeight=FontWeight.Black,color=Color(0xFFFF3EBA))
                Text(name,fontSize=24.sp,color=Color(0xFF00FFE9),textAlign=TextAlign.Center)
                Button(onClick={command("com.geoklaxon.SNOOZE_PLAYBACK",token);finish()},
                    modifier=Modifier.fillMaxWidth().height(64.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF13EFE4))) {
                    Text("SNOOZE",color=Color(0xFF160725),fontSize=24.sp,fontWeight=FontWeight.Bold)
                }
                Button(onClick={command("com.geoklaxon.STOP",token);finish()},
                    modifier=Modifier.fillMaxWidth().height(90.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFFFEB00))) {
                    Text("DISMISS!",color=Color(0xFF160725),fontSize=29.sp,fontWeight=FontWeight.Black)
                }
            }
        }
    }
    private fun command(action:String, token:String) {
        startService(Intent(this,AlarmPlaybackService::class.java).apply {
            this.action=action;data=Uri.parse("geoklaxon://$action/$token");putExtra("session_token",token)
        })
    }
}
