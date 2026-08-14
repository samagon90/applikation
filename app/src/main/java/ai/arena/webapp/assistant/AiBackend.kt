package ai.arena.webapp.assistant

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Бесплатный LLM-бэкенд (pollinations.ai, без API-ключа) для свободных
 * вопросов, на которые не отвечает локальная база знаний помощника.
 * При любой сетевой ошибке возвращает null — вызывающий код показывает
 * локальный фолбэк, так что помощник работает всегда.
 */
object AiBackend {

    private const val SYSTEM_PROMPT_EN =
        "You are a helpful assistant inside an Android app for arena.ai (LM Arena), " +
            "a free multi-model AI platform. You know the site: Battle Mode (compare two " +
            "anonymous models and vote), Direct chat, Leaderboard (categories: text, code, " +
            "webdev, agent, vision, document, image, video, search), Search/history, WebDev " +
            "templates (landing page, dashboard, game, fullstack app). Help users write " +
            "prompts and pick the right model/mode. Answer briefly (up to 120 words) in the " +
            "language of the question."

    private const val SYSTEM_PROMPT_RU =
        "Ты — помощник внутри Android-приложения для сайта arena.ai (LM Arena), " +
            "бесплатной мультимодельной платформы. Ты знаешь сайт: Battle Mode (сравнение " +
            "двух анонимных моделей с голосованием), Direct-чат, лидерборд (категории: " +
            "текст, код, веб-разработка, агенты, зрение, документы, картинки, видео, поиск), " +
            "поиск и историю, WebDev-шаблоны (лендинг, дашборд, игра, fullstack-приложение). " +
            "Помогай составлять промпты и выбирать модель или режим. Отвечай кратко " +
            "(до 120 слов) на языке вопроса."

    /** Асинхронный запрос к LLM; результат (или null) приходит в главном потоке? Нет — в фоновом. */
    fun ask(prompt: String, ruLocale: Boolean, onResult: (String?) -> Unit) {
        Thread({
            onResult(request(prompt, if (ruLocale) SYSTEM_PROMPT_RU else SYSTEM_PROMPT_EN))
        }, "ai-backend").start()
    }

    private fun request(prompt: String, system: String): String? {
        return try {
            val url = "https://text.pollinations.ai/" +
                URLEncoder.encode(prompt, "UTF-8") +
                "?system=" + URLEncoder.encode(system, "UTF-8") +
                "&model=openai"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "ArenaAI/3.0 (Android)")
            conn.setRequestProperty("Accept", "text/plain")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            text.trim().ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }
}
