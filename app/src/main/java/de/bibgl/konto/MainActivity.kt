package de.bibgl.konto

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bibgl.konto.ui.AccountScreen
import de.bibgl.konto.ui.AccountViewModel
import de.bibgl.konto.ui.BibTheme
import de.bibgl.konto.ui.LoginScreen
import de.bibgl.konto.ui.SettingsSheet
import de.bibgl.konto.work.Notifications

class MainActivity : ComponentActivity() {

    companion object {
        /** Aus einer Benachrichtigung: direkt diesen Ausweis anzeigen. */
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private val viewModel: AccountViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannel(this)
        openProfileFrom(intent)

        setContent {
            BibTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var settingsOpen by remember { mutableStateOf(false) }

                // Erst nach erfolgreicher Anmeldung nach der Benachrichtigungs-
                // Erlaubnis fragen - vorher waere der Grund fuer den Nutzer unklar.
                LaunchedEffect(state.profiles.isNotEmpty()) {
                    if (state.profiles.isNotEmpty() &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (state.showLogin) {
                    LoginScreen(
                        state = state,
                        onLogin = viewModel::addProfile,
                        isAdditional = state.addingProfile && state.profiles.isNotEmpty(),
                        onCancel = viewModel::cancelAddProfile,
                    )
                } else {
                    AccountScreen(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onRenew = { copyId -> viewModel.renew(copyId) },
                        onRenewAll = { viewModel.renewAll() },
                        onRemoveFromWatchlist = viewModel::removeFromWatchlist,
                        onOpenSettings = { settingsOpen = true },
                        onLogoutAll = viewModel::logoutAll,
                        onMessageShown = viewModel::consumeMessage,
                        onConfirmFees = viewModel::confirmFees,
                        onDismissFees = viewModel::dismissFees,
                        onSwitchProfile = viewModel::switchProfile,
                        onAddProfile = viewModel::beginAddProfile,
                        onRenameProfile = viewModel::renameProfile,
                        onRemoveProfile = viewModel::removeProfile,
                    )
                    if (settingsOpen) {
                        SettingsSheet(
                            state = state,
                            onDismiss = { settingsOpen = false },
                            onNotificationsChanged = viewModel::setNotificationsEnabled,
                            onReminderDaysChanged = viewModel::setReminderDays,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openProfileFrom(intent)
    }

    private fun openProfileFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PROFILE_ID)?.let { viewModel.switchProfile(it) }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.state.value.activeProfileId != null) viewModel.refresh()
    }
}
