package com.dissonance.r2sync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dissonance.r2sync.model.R2Config
import com.dissonance.r2sync.ui.ConnectionStatus
import com.dissonance.r2sync.ui.theme.CfOrangePrimary
import com.dissonance.r2sync.ui.theme.CrimsonRed
import com.dissonance.r2sync.ui.theme.EmeraldGreen

@Composable
fun R2ConfigDialog(
    config: R2Config,
    connectionStatus: ConnectionStatus,
    onDismiss: () -> Unit,
    onSave: (R2Config) -> Unit,
    onTestConnection: (R2Config) -> Unit
) {
    var accountId by remember { mutableStateOf(config.accountId) }
    var accessKeyId by remember { mutableStateOf(config.accessKeyId) }
    var secretAccessKey by remember { mutableStateOf(config.secretAccessKey) }
    var bucketName by remember { mutableStateOf(config.bucketName) }
    var customEndpoint by remember { mutableStateOf(config.customEndpoint) }
    var showSecret by remember { mutableStateOf(false) }

    val currentConfig = R2Config(
        accountId = accountId,
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        bucketName = bucketName,
        customEndpoint = customEndpoint,
        region = "auto"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(CfOrangePrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = CfOrangePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Cloudflare R2 Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Enter your Cloudflare R2 API Tokens (S3-compatible credentials from Cloudflare Dashboard > R2):",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text("Cloudflare Account ID") },
                    placeholder = { Text("e.g. 1a2b3c4d5e6f7g8h9i0j") },
                    modifier = Modifier.fillMaxWidth().testTag("r2_account_id_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = bucketName,
                    onValueChange = { bucketName = it },
                    label = { Text("Bucket Name") },
                    placeholder = { Text("e.g. device-sync-vault") },
                    
                    modifier = Modifier.fillMaxWidth().testTag("r2_bucket_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = accessKeyId,
                    onValueChange = { accessKeyId = it },
                    label = { Text("Access Key ID") },
                    
                    modifier = Modifier.fillMaxWidth().testTag("r2_access_key_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = secretAccessKey,
                    onValueChange = { secretAccessKey = it },
                    label = { Text("Secret Access Key") },
                    
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Secret"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("r2_secret_key_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = customEndpoint,
                    onValueChange = { customEndpoint = it },
                    label = { Text("Custom S3 Endpoint (Optional)") },
                    placeholder = { Text("Leave blank for default .r2.cloudflarestorage.com") },
                    
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Connection Test Status
                when (connectionStatus) {
                    is ConnectionStatus.Testing -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CfOrangePrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing connection to R2...", fontSize = 12.sp)
                        }
                    }
                    is ConnectionStatus.Success -> {
                        Surface(
                            color = EmeraldGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Connected successfully (${connectionStatus.latencyMs}ms latency)",
                                    color = EmeraldGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    is ConnectionStatus.Error -> {
                        Surface(
                            color = CrimsonRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Error: ${connectionStatus.message}",
                                    color = CrimsonRed,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    else -> {}
                }

                OutlinedButton(
                    onClick = { onTestConnection(currentConfig) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(currentConfig) },
                colors = ButtonDefaults.buttonColors(containerColor = CfOrangePrimary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("save_r2_config_button")
            ) {
                Text("Save", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Cancel", maxLines = 1, softWrap = false)
            }
        }
    )
}
