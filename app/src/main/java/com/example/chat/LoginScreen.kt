package com.example.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(state: UiState, onSubmit: (Boolean, String, String) -> Unit) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(8.dp))
            Text("Freedom", style = MaterialTheme.typography.displaySmall)
            Text(
                if (register) "Создайте аккаунт" else "Войдите, чтобы продолжить",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                user, { user = it }, Modifier.fillMaxWidth(),
                label = { Text("Имя пользователя") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it }, Modifier.fillMaxWidth(),
                label = { Text("Пароль") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "🔒 Сквозное шифрование: пароль защищает ваши ключи и не отправляется на сервер. " +
                    "Восстановить его невозможно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(register, user, pass) },
                enabled = !state.loading && user.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.loading) "Ключи шифрования…" else if (register) "Создать аккаунт" else "Войти")
            }
            TextButton(onClick = { register = !register }, Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Регистрация")
            }
        }
    }
}
