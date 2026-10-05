#!/bin/bash
# إضافة Gemini (مفتاح مجاني) كخيار للترجمة الذكية إلى جانب Claude
cd "$(dirname "$0")" || exit 1
F=app/src/main/java/eu/kanade/tachiyomi/mslime/MslTranslate.kt
[ -f "$F" ] || { echo "[!!] $F not found: run msl_ai.sh first"; exit 1; }
python3 - <<'EOF'
p='app/src/main/java/eu/kanade/tachiyomi/mslime/MslTranslate.kt'
s=open(p).read()
if 'translateGemini' in s:
    print('[ok] already patched'); raise SystemExit
old='    fun translate(key: String, texts: List<String>): List<String>? {'
if old not in s:
    print('[!!] translate() not found'); raise SystemExit(1)
new='''    fun translate(key: String, texts: List<String>): List<String>? =
        if (key.startsWith("AIza")) translateGemini(key, texts) else translateClaude(key, texts)

    private fun translateGemini(key: String, texts: List<String>): List<String>? {
        return try {
            val arr = JSONArray()
            texts.forEach { arr.put(it) }
            val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
            val user = JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", arr.toString())))
            val body = JSONObject()
                .put("systemInstruction", sys)
                .put("contents", JSONArray().put(user))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                .toString()
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream.bufferedReader().readText()
            if (code !in 200..299) return null
            var out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val a = JSONArray(out)
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun translateClaude(key: String, texts: List<String>): List<String>? {'''
s=s.replace(old,new,1)
s=s.replace('.setTitle("مفتاح الترجمة الذكية (Anthropic API)")','.setTitle("مفتاح الترجمة الذكية (Gemini مجاني أو Claude)")')
s=s.replace('et.hint = "sk-ant-..."','et.hint = "AIza... أو sk-ant-..."')
open(p,'w').write(s); print('[ok] Gemini support added')
EOF
if [ -z "$SKIP_GIT" ]; then
  git add -A && git commit -m "Add Gemini free key option" && git push && echo "[ok] pushed"
fi
