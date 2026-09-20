package com.example.vixremote

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var logView: TextView
    private lateinit var ipField: EditText
    private lateinit var pinField: EditText
    private lateinit var appIdField: EditText
    private lateinit var nsField: EditText
    private lateinit var msgField: EditText
    private var pairToken = 0

    // La TV usa un certificado propio, por eso se acepta sin validar (solo red local).
    private val sslCtx: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vix", Context.MODE_PRIVATE)
        buildUi()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun edit(hint: String, value: String?) = EditText(this).apply {
        this.hint = hint
        setText(value ?: "")
        setSingleLine(true)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(label("ViX Remote", 24f, true))

        // Boton principal
        val vix = Button(this).apply {
            text = "ABRIR ViX"
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF6A00"))
            setOnClickListener { action { launchVix() } }
        }
        root.addView(vix, LinearLayout.LayoutParams(-1, dp(120)))

        // Controles basicos
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(t: String, cs: Int, code: Int) {
            row.addView(button(t) { action { key(cs, code) } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        small("Power", 11, 2)
        small("Vol -", 5, 0)
        small("Vol +", 5, 1)
        small("Mute", 5, 4)
        root.addView(row)

        // Configuracion
        root.addView(label("Configuración (solo la primera vez)", 16f, true))

        root.addView(label("1. IP de la TV"))
        ipField = edit("Ej. 192.168.1.50", prefs.getString("ip", ""))
        root.addView(ipField)
        root.addView(button("Buscar TV en la red") { action { discover() } })

        root.addView(label("2. Emparejar"))
        root.addView(button("Pedir PIN (aparece en la TV)") { action { pairStart() } })
        pinField = edit("PIN que muestra la TV", "")
        root.addView(pinField)
        root.addView(button("Confirmar PIN") { action { pairFinish() } })

        root.addView(label("3. Datos de ViX"))
        root.addView(label("Abre ViX en la TV y luego toca 'Detectar'. Si ya sabes los valores, escríbelos."))
        appIdField = edit("APP_ID", prefs.getString("appId", ""))
        nsField = edit("NAME_SPACE (normalmente 4 o 2)", prefs.getString("ns", "4"))
        msgField = edit("MESSAGE (déjalo vacío si no hay)", prefs.getString("msg", ""))
        root.addView(appIdField)
        root.addView(nsField)
        root.addView(msgField)
        root.addView(button("Detectar ViX (ábrela primero en la TV)") { action { detectApp() } })
        root.addView(button("Guardar valores") { action { say("Valores guardados.") } })

        root.addView(label("Registro", 16f, true))
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)
    }

    // ---------------------------------------------------------------- Helpers

    private fun say(msg: String) = runOnUiThread { logView.append(msg + "\n\n") }

    /** Guarda los campos (en el hilo de UI) y luego ejecuta la tarea en segundo plano. */
    private fun action(task: () -> Unit) {
        prefs.edit()
            .putString("ip", ipField.text.toString().trim())
            .putString("pin", pinField.text.toString().trim())
            .putString("appId", appIdField.text.toString().trim())
            .putString("ns", nsField.text.toString().trim())
            .putString("msg", msgField.text.toString().trim())
            .apply()
        Thread {
            try {
                task()
            } catch (e: Exception) {
                say("Error: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun ensurePort(ip: String): Int {
        if (ip.isEmpty()) throw Exception("Escribe la IP de la TV o usa 'Buscar TV'.")
        for (p in intArrayOf(7345, 9000)) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, p), 1500) }
                return p
            } catch (_: Exception) {
            }
        }
        throw Exception("No pude conectar con la TV en $ip (puertos 7345 y 9000). ¿Está encendida y en el mismo Wi-Fi?")
    }

    private fun call(method: String, path: String, body: String? = null, useAuth: Boolean = true): String {
        val ip = prefs.getString("ip", "") ?: ""
        val port = ensurePort(ip)
        val c = URL("https://$ip:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = sslCtx.socketFactory
        c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        val auth = prefs.getString("auth", "") ?: ""
        if (useAuth && auth.isNotEmpty()) c.setRequestProperty("AUTH", auth)
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
    }

    // ---------------------------------------------------------------- Acciones

    private fun discover() {
        say("Buscando TV Vizio...")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("vix")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 3500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-kinoma-com:device:shell:1\r\n\r\n").toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val p = DatagramPacket(ByteArray(2048), 2048)
                s.receive(p)
                val ip = p.address.hostAddress ?: ""
                prefs.edit().putString("ip", ip).apply()
                runOnUiThread { ipField.setText(ip) }
                say("TV encontrada: $ip")
            }
        } catch (e: SocketTimeoutException) {
            say("No encontré la TV automáticamente. Escribe la IP a mano (en la TV: Menú > Red > Ver detalles).")
        } finally {
            lock.release()
        }
    }

    private fun pairStart() {
        val body = JSONObject().put("DEVICE_NAME", "ViX Remote").put("DEVICE_ID", "vixremote1").toString()
        val r = call("PUT", "/pairing/start", body, false)
        say("Respuesta: $r")
        pairToken = JSONObject(r).optJSONObject("ITEM")?.optInt("PAIRING_REQ_TOKEN", 0) ?: 0
        if (pairToken != 0) say("Escribe el PIN que aparece en la TV y toca 'Confirmar PIN'.")
    }

    private fun pairFinish() {
        val pin = prefs.getString("pin", "") ?: ""
        val body = JSONObject()
            .put("DEVICE_ID", "vixremote1")
            .put("CHALLENGE_TYPE", 1)
            .put("RESPONSE_VALUE", pin)
            .put("PAIRING_REQ_TOKEN", pairToken)
            .toString()
        val r = call("PUT", "/pairing/pair", body, false)
        val token = JSONObject(r).optJSONObject("ITEM")?.optString("AUTH_TOKEN", "") ?: ""
        if (token.isNotEmpty()) {
            prefs.edit().putString("auth", token).apply()
            say("¡Emparejado! Ahora abre ViX en la TV y usa 'Detectar ViX'.")
        } else {
            say("No se pudo emparejar. Respuesta: $r")
        }
    }

    private fun detectApp() {
        val r = call("GET", "/app/current")
        say("Respuesta de la TV: $r")
        val item = JSONObject(r).optJSONObject("ITEM")
        val v = item?.optJSONObject("VALUE") ?: item
        val id = v?.optString("APP_ID", "") ?: ""
        if (id.isEmpty()) {
            say("No pude leer la app actual. Asegúrate de tener ViX abierta y de estar emparejado. Mándame la respuesta de arriba.")
            return
        }
        val ns = v?.optInt("NAME_SPACE", 4) ?: 4
        val msg = if (v == null || v.isNull("MESSAGE")) "" else v.optString("MESSAGE", "")
        prefs.edit().putString("appId", id).putString("ns", ns.toString()).putString("msg", msg).apply()
        runOnUiThread {
            appIdField.setText(id)
            nsField.setText(ns.toString())
            msgField.setText(msg)
        }
        say("ViX detectada y guardada: APP_ID=$id, NAME_SPACE=$ns")
    }

    private fun launchVix() {
        val id = prefs.getString("appId", "") ?: ""
        if (id.isEmpty()) {
            say("Primero configura ViX (abajo: 'Detectar ViX').")
            return
        }
        val ns0 = (prefs.getString("ns", "4") ?: "4").toIntOrNull() ?: 4
        val msg = prefs.getString("msg", "") ?: ""
        val order = listOf(ns0) + listOf(4, 2).filter { it != ns0 }
        for (ns in order) {
            val value = JSONObject()
                .put("NAME_SPACE", ns)
                .put("APP_ID", id)
                .put("MESSAGE", if (msg.isEmpty()) JSONObject.NULL else msg)
            val r = call("PUT", "/app/launch", JSONObject().put("VALUE", value).toString())
            say("Intento con NAME_SPACE=$ns: $r")
            if (r.contains("SUCCESS")) return
        }
        say("No se pudo abrir ViX. Mándame el registro de arriba.")
    }

    private fun key(codeset: Int, code: Int) {
        val body = "{\"KEYLIST\":[{\"CODESET\":$codeset,\"CODE\":$code,\"ACTION\":\"KEYPRESS\"}]}"
        say(call("PUT", "/key_command/", body))
    }
}
