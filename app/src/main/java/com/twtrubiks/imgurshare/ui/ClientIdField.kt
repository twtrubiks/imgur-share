package com.twtrubiks.imgurshare.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.twtrubiks.imgurshare.R

/**
 * Client-ID 輸入框：預設隱藏，避免截圖、錄影、分享螢幕時外流（被別人拿去用會吃掉這組 Client-ID 的額度）。
 * 鍵盤用密碼類型，輸入法不會把它學進字典、也不會出現在建議字裡；顯示出來時也維持密碼類型。
 */
@Composable
fun ClientIdField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Client-ID") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (visible) "隱藏 Client-ID" else "顯示 Client-ID",
                )
            }
        },
        modifier = modifier,
    )
}
