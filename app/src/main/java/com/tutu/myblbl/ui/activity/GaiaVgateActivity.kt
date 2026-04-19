package com.tutu.myblbl.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.security.NetworkWebGateway
import com.tutu.myblbl.network.session.NetworkSessionGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.json.JSONObject

class GaiaVgateActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var webView: WebView
    private val sessionGateway: NetworkSessionGateway by inject()
    private val webGateway: NetworkWebGateway by inject()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vVoucher = intent.getStringExtra(EXTRA_V_VOUCHER).orEmpty().trim()
        if (vVoucher.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        status = TextView(this).apply { text = "正在请求验证…" }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(webView)
        }
        setContentView(root)

        lifecycleScope.launch {
            try {
                val reg = withContext(Dispatchers.IO) {
                    gaiaVgateRegister(vVoucher, sessionGateway, webGateway)
                }
                status.text = "请完成验证…"
                val bridge = Bridge(token = reg.token)
                webView.addJavascriptInterface(bridge, "Android")
                webView.loadDataWithBaseURL(
                    "https://api.bilibili.com/",
                    geetestHtml(gt = reg.gt, challenge = reg.challenge),
                    "text/html", "utf-8", null,
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "register failed", t)
                status.text = "申请失败：${t.message}"
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class Bridge(private val token: String) {
        @JavascriptInterface
        fun onGeetestResult(validate: String?, seccode: String?, geetestChallenge: String?) {
            val v = validate.orEmpty().trim()
            val s = seccode.orEmpty().trim()
            val c = geetestChallenge.orEmpty().trim()
            if (v.isBlank() || s.isBlank() || c.isBlank()) return

            lifecycleScope.launch {
                try {
                    status.text = "验证成功，正在提交…"
                    val grisk = withContext(Dispatchers.IO) {
                        gaiaVgateValidate(
                            token = token,
                            geetestChallenge = c,
                            validate = v,
                            seccode = s,
                            sessionGateway = sessionGateway,
                            webGateway = webGateway
                        )
                    }
                    val out = Intent().putExtra(EXTRA_GAIA_VTOKEN, grisk)
                    setResult(RESULT_OK, out)
                    finish()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "validate failed", t)
                    status.text = "提交失败：${t.message}"
                }
            }
        }
    }

    private fun geetestHtml(gt: String, challenge: String): String {
        val safeGt = gt.replace("'", "\\'")
        val safeChallenge = challenge.replace("'", "\\'")
        return """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <script src="https://static.geetest.com/static/tools/gt.js"></script>
  <style>
    body { font-family: sans-serif; margin: 0; padding: 12px; background:#1a1a2e; color:#fff; }
    #captcha { margin-top: 12px; }
    #btnStart { margin-top: 12px; padding: 10px 14px; font-size: 16px; }
  </style>
</head>
<body>
    <div>人机验证</div>
    <button id="btnStart" disabled>开始验证</button>
  <div id="captcha"></div>
  <script>
    function start() {
      if (typeof initGeetest !== 'function') {
        document.body.innerHTML = '<div>加载验证码脚本失败，请检查网络</div>';
        return;
      }
      initGeetest({
        gt: '$safeGt',
        challenge: '$safeChallenge',
        new_captcha: true,
        product: 'bind',
        offline: false,
        https: true
      }, function (captchaObj) {
        captchaObj.appendTo('#captcha');
        captchaObj.onReady(function () {
          var btn = document.getElementById('btnStart');
          if (btn) btn.disabled = false;
        });
        var btn = document.getElementById('btnStart');
        if (btn) {
          btn.addEventListener('click', function () {
            captchaObj.verify();
          });
        }
        captchaObj.onSuccess(function () {
          var res = captchaObj.getValidate();
          if (!res) return;
          if (window.Android && window.Android.onGeetestResult) {
            window.Android.onGeetestResult(res.geetest_validate, res.geetest_seccode, res.geetest_challenge);
          }
        });
      });
    }
    start();
  </script>
</body>
</html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "GaiaVgate"
        const val EXTRA_V_VOUCHER = "v_voucher"
        const val EXTRA_GAIA_VTOKEN = "gaia_vtoken"
    }
}

private data class GaiaVgateRegisterResult(
    val gt: String,
    val challenge: String,
    val token: String
)

private suspend fun gaiaVgateRegister(
    vVoucher: String,
    sessionGateway: NetworkSessionGateway,
    webGateway: NetworkWebGateway
): GaiaVgateRegisterResult {
    val csrf = sessionGateway.getCsrfToken()
    val baseUrl = "https://api.bilibili.com/x/gaia-vgate/v1/register"
    val url = if (csrf.isNotBlank()) "$baseUrl?csrf=$csrf" else baseUrl

    val form = mutableMapOf("v_voucher" to vVoucher)
    if (csrf.isNotBlank()) form["csrf"] = csrf

    val headers = webGateway.buildPiliWebHeaders(url, includeCookie = true)
    val json = webGateway.postFormJson(url, form, headers)

    val code = json.optInt("code", 0)
    if (code != 0) {
        val msg = json.optString("message", json.optString("msg", ""))
        throw RuntimeException("gaia_vgate_register code=$code msg=$msg")
    }
    val data = json.optJSONObject("data") ?: JSONObject()
    val token = data.optString("token", "").trim()
    val geetest = data.optJSONObject("geetest") ?: JSONObject()
    val gt = geetest.optString("gt", "").trim()
    val challenge = geetest.optString("challenge", "").trim()
    if (token.isBlank() || gt.isBlank() || challenge.isBlank()) {
        throw RuntimeException("gaia_vgate_register invalid data: token=${token.take(8)} gt=${gt.take(6)} challenge=${challenge.take(6)}")
    }
    return GaiaVgateRegisterResult(gt = gt, challenge = challenge, token = token)
}

private suspend fun gaiaVgateValidate(
    token: String,
    geetestChallenge: String,
    validate: String,
    seccode: String,
    sessionGateway: NetworkSessionGateway,
    webGateway: NetworkWebGateway
): String {
    val csrf = sessionGateway.getCsrfToken()
    val baseUrl = "https://api.bilibili.com/x/gaia-vgate/v1/validate"
    val url = if (csrf.isNotBlank()) "$baseUrl?csrf=$csrf" else baseUrl

    val form = mutableMapOf(
        "token" to token,
        "challenge" to geetestChallenge,
        "validate" to validate,
        "seccode" to seccode
    )
    if (csrf.isNotBlank()) form["csrf"] = csrf

    val headers = webGateway.buildPiliWebHeaders(url, includeCookie = true)
    val json = webGateway.postFormJson(url, form, headers)

    val code = json.optInt("code", 0)
    if (code != 0) {
        val msg = json.optString("message", json.optString("msg", ""))
        throw RuntimeException("gaia_vgate_validate code=$code msg=$msg")
    }
    val data = json.optJSONObject("data") ?: JSONObject()
    val isValid = data.optInt("is_valid", 0)
    if (isValid != 1) throw RuntimeException("gaia_vgate_validate_invalid")
    val griskId = data.optString("grisk_id", "").trim()
    if (griskId.isBlank()) throw RuntimeException("gaia_vgate_validate_missing_grisk_id")
    return griskId
}
