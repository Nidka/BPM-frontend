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
import androidx.compose.animation.core.snap
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
import pe.bpm.resources.bpm_logo
import pe.bpm.resources.mascot_learning
import pe.bpm.resources.mascot_welcome

object BpmBrand {
    // El prototipo declara su nombre provisional; BPM identifica el proyecto mientras se define la marca final.
    const val name = "BPM"
}

@Composable fun Brand(compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // Logo «gorro con visto»: el vector ya incluye el fondo teal redondeado.
        Image(painterResource(Res.drawable.bpm_logo), contentDescription = null, modifier = Modifier.size(36.dp))
        if (!compact) Text(BpmBrand.name, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable fun Mascot(modifier:Modifier=Modifier,learning:Boolean=false){Image(painterResource(if(learning)Res.drawable.mascot_learning else Res.drawable.mascot_welcome),contentDescription=null,modifier=modifier)}
/**
 * Tarjeta del sistema. [BpmCardVariant.Standard] relleno 24 dp; [BpmCardVariant.Compact] relleno 16 dp para
 * listas y KPI; [BpmCardVariant.Featured] fondo teal suave, una por pantalla. Un `tint` explícito manda sobre la variante.
 */
@Composable fun BpmCard(modifier:Modifier=Modifier,tint:Color=Color.Unspecified,variant:BpmCardVariant=BpmCardVariant.Standard,content:@Composable ColumnScope.()->Unit){
    val shape=RoundedCornerShape(24.dp)
    val border=MaterialTheme.colorScheme.outlineVariant
    val fill=when{
        tint!=Color.Unspecified->tint
        variant==BpmCardVariant.Featured->MaterialTheme.colorScheme.primaryContainer
        else->MaterialTheme.colorScheme.surface
    }
    val padding=if(variant==BpmCardVariant.Compact)BpmSpacing.cardCompact else BpmSpacing.card
    // Borde de 2 dp y relieve de 3 dp, como `.course-card` en playful.css.
    Box(modifier.bpmRelief(border,shape,3.dp),propagateMinConstraints=true){
        Surface(shape=shape,color=fill,border=BorderStroke(2.dp,border)){
            Column(Modifier.padding(padding),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
    }
}
@Composable fun RaisedButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    size:BpmButtonSize=BpmButtonSize.Normal) =
    RaisedButtonImpl(label, onClick, modifier, enabled && !pe.bpm.core.offline.serverActionBlocked(), size)

@Composable private fun RaisedButtonImpl(label:String,onClick:()->Unit,modifier:Modifier,enabled:Boolean,size:BpmButtonSize){
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    // Con «reducir movimiento» el hundimiento es instantáneo: el cambio de estado se ve, sin desplazamiento animado.
    val reduceMotion = rememberReduceMotion()
    val offset by animateDpAsState(if (pressed && enabled) 2.dp else 0.dp, if (reduceMotion) snap() else tween(100), label = "Presión del botón")
    Box(modifier.bpmRelief(if(enabled)BpmTokens.primaryRelief else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp),BpmSpacing.reliefDepth)){
        BpmFeedback(MaterialTheme.colorScheme.onPrimary) {
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=if(size==BpmButtonSize.Compact)BpmSpacing.buttonCompact else BpmSpacing.button).offset(y = offset),
            interactionSource = interactions,
            border = if (focused && enabled) BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary) else null,
            shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)){
            // El peso (negrita) viene de `labelLarge` en el tema.
            Text(label)
        }
        }
    }
}
@Composable fun Eyebrow(text:String){Text(text,style=BpmType.labelStrong,color=MaterialTheme.colorScheme.primary)}
@Composable fun EmptyState(title:String,description:String){BpmCard(Modifier.fillMaxWidth()){
    Text(title,style=MaterialTheme.typography.titleMedium)
    Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)
}}
