package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_learning
import pe.bpm.resources.mascot_welcome

object BpmBrand {
    // El prototipo declara su nombre provisional; BPM identifica el proyecto mientras se define la marca final.
    const val name = "BPM"
}

@Composable fun Brand(compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
            Box(Modifier.size(36.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                BpmIcon(BpmSymbol.Sprout, tint = Color.White)
            }
        }
        if (!compact) Text(BpmBrand.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
@Composable fun Mascot(modifier:Modifier=Modifier,learning:Boolean=false){Image(painterResource(if(learning)Res.drawable.mascot_learning else Res.drawable.mascot_welcome),contentDescription=null,modifier=modifier)}
@Composable fun BpmCard(modifier:Modifier=Modifier,tint:Color=MaterialTheme.colorScheme.surface,content:@Composable ColumnScope.()->Unit){
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=tint,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable fun RaisedButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val offset by animateDpAsState(if (pressed && enabled) 2.dp else 0.dp, tween(100), label = "Presión del botón")
    Box(modifier.background(if(enabled)Color(0xFF064D49)else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp)).padding(bottom=4.dp)){
        BpmFeedback(MaterialTheme.colorScheme.onPrimary) {
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).offset(y = offset),
            interactionSource = interactions,
            border = if (focused && enabled) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary) else null,
            shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)){
            Text(label,fontWeight=FontWeight.Bold)
        }
        }
    }
}
@Composable fun Eyebrow(text:String){Text(text,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)}
@Composable fun EmptyState(title:String,description:String){BpmCard(Modifier.fillMaxWidth()){
    Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)
}}
