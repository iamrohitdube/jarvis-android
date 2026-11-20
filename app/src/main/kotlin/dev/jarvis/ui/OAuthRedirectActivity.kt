package dev.jarvis.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives dev.jarvis://oauth/<connector>?code=...&state=... from the browser. It returns to the
 * main screen straight away (which also closes the Custom Tab) and completes the token exchange in
 * the app scope, reporting the outcome through [SignInEvents]. The URI is never logged.
 */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {
    @Inject lateinit var indMoney: IndMoneyConnector
    @Inject lateinit var events: SignInEvents
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        // savedInstanceState != null means a re-creation: the redirect was already handled.
        if (savedInstanceState == null && uri != null && uri.path?.trim('/') == "indmoney") {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            val error = uri.getQueryParameter("error")
            events.post("Finishing INDmoney sign-in…")
            appScope.launch {
                val result = indMoney.completeSignIn(code, state, error)
                events.post(
                    result.fold(
                        onSuccess = { "INDmoney connected." },
                        onFailure = { "INDmoney sign-in failed: ${it.message}" },
                    )
                )
            }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
