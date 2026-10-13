package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_learning
import pe.bpm.resources.mascot_welcome

@Composable fun Brand(compact:Boolean=false){
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primary){Text("b.",Modifier.padding(horizontal=13.dp,vertical=5.dp),color=Color.White,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black)}
        if(!compact) Column { Text("BPM Aula",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.ExtraBold); Text("APRENDE. CUIDA. CRECE.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun Mascot(modifier:Modifier=Modifier,learning:Boolean=false){Image(painterResource(if(learning)Res.drawable.mascot_learning else Res.drawable.mascot_welcome),contentDescription=null,modifier=modifier)}
@Composable fun BpmCard(modifier:Modifier=Modifier,tint:Color=MaterialTheme.colorScheme.surface,content:@Composable ColumnScope.()->Unit){
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=tint,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable fun RaisedButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){
    Box(modifier.background(if(enabled)Color(0xFF064D49)else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp)).padding(bottom=4.dp)){
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)){
            Text(label,fontWeight=FontWeight.Bold)
        }
    }
}
@Composable fun Eyebrow(text:String){Text(text,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)}
@Composable fun EmptyState(title:String,description:String){BpmCard(Modifier.fillMaxWidth()){
    Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)
}}
