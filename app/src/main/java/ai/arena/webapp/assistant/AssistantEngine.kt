package ai.arena.webapp.assistant

import java.util.Locale

/**
 * Встроенный AI-помощник arena.ai: работает локально (мгновенно и офлайн).
 *
 * Умеет три вещи:
 *  1. Составлять готовые промпты под задачу (роль + требования + формат);
 *  2. Подсказывать, какая модель/режим сайта лучше подходит задаче
 *     (код, веб-разработка, картинки, видео, поиск, рассуждения и т.д.);
 *  3. Быть путеводителем по сайту (Battle Mode, Direct, лидерборд,
 *     история, WebDev-шаблоны, советы).
 *
 * Для свободных вопросов вне базы знаний MainActivity дополнительно
 * спрашивает бесплатный LLM (AiBackend) с фолбэком на локальные ответы.
 */
class AssistantEngine(locale: Locale) {

    private val ru = locale.language.equals("ru", ignoreCase = true)

    data class Reply(
        val text: String,
        val chips: List<String> = emptyList()
    )

    /** t(ru, en) — локализованная строка. */
    private fun t(ru: String, en: String): String = if (this.ru) ru else en

    // ------------------------------------------------------------------ чипы

    fun chips(): List<String> = listOf(
        t("Составь промпт", "Write a prompt"),
        t("Какая модель лучше?", "Which model is best?"),
        t("Что такое Battle Mode?", "What is Battle Mode?"),
        t("Как работает лидерборд?", "How does the leaderboard work?"),
        t("Где история чатов?", "Where is my chat history?"),
        t("Сайт за 5 минут", "The site in 5 minutes")
    )

    fun greeting(): Reply = Reply(
        t(
            "Привет! Я — встроенный помощник по arena.ai. Могу:\n" +
                "• составить готовый промпт под вашу задачу;\n" +
                "• подсказать, какая модель или режим подойдут лучше;\n" +
                "• провести по сайту: Battle Mode, лидерборд, история, шаблоны.\n\n" +
                "Опишите задачу — например, «нужен лендинг для кофейни» или " +
                "«какая модель лучше для кода» — или нажмите на подсказку ниже.",
            "Hi! I'm the built-in arena.ai assistant. I can:\n" +
                "• write a ready-to-use prompt for your task;\n" +
                "• suggest the best model or mode for it;\n" +
                "• guide you around the site: Battle Mode, leaderboard, history, templates.\n\n" +
                "Describe your task — e.g. \"a landing page for a coffee shop\" or " +
                "\"which model is best for coding\" — or tap a suggestion below."
        )
    )

    fun fallbackReply(): Reply = Reply(
        t(
            "Я лучше всего разбираюсь в arena.ai: помогу составить промпт, выбрать модель " +
                "и найти нужный раздел сайта. Опишите задачу — например: «нужна картинка " +
                "в стиле киберпанк» или «какая модель лучше для математики».",
            "I'm best at arena.ai itself: prompts, model choice and site navigation. " +
                "Describe your task — e.g. \"a cyberpunk-style image\" or " +
                "\"which model is best for math\"."
        )
    )

    fun onChip(chip: String): Reply {
        val q = chip.lowercase()
        return when {
            q.contains("промпт") || q.contains("prompt") ->
                promptAdvice(GENERAL, "")
            q.contains("модел") || q.contains("model") ->
                modelAdvice(GENERAL, "")
            q.contains("battle") ->
                Reply(guideBattle(), listOf(t("Составь промпт", "Write a prompt")))
            q.contains("лидерборд") || q.contains("leaderboard") ->
                Reply(guideLeaderboard())
            q.contains("истори") || q.contains("history") ->
                Reply(guideHistory())
            else -> Reply(guideHow())
        }
    }

    // ------------------------------------------------------------- интенты

    fun respond(raw: String): Reply? {
        val q = raw.lowercase().trim()
        if (q.isEmpty()) return null

        if (isGreeting(q)) return greeting()

        val domain = detectDomain(q)

        return when {
            isPromptIntent(q) -> promptAdvice(domain, cleanTask(raw))
            isModelIntent(q) -> modelAdvice(domain, cleanTask(raw))
            isGuideIntent(q) -> guideAnswer(q)
            // Описание задачи без слов «промпт/модель» — составляем запрос сами
            domain != GENERAL -> promptAdvice(domain, cleanTask(raw))
            else -> null // → бесплатный LLM с фолбэком
        }
    }

    private fun isGreeting(q: String): Boolean =
        q in listOf("привет", "здравствуй", "здравствуйте", "хай", "hello", "hi", "hey") ||
            q.contains("привет") || q.contains("здравств") || q.contains("hello") ||
            q == "hi" || q == "hey" || q.contains("помощь") || q.contains("help") ||
            q.contains("что ты умеешь") || q.contains("what can you do")

    private fun isPromptIntent(q: String): Boolean =
        q.contains("промпт") || q.contains("prompt") ||
            q.contains("составь") || q.contains("сформулиру") ||
            q.contains("напиши") || q.contains("запрос") ||
            q.contains("как спросить") || q.contains("как задать") ||
            q.contains("придумай") || q.contains("сделай запрос") ||
            q.contains("write") || q.contains("compose") || q.contains("draft")

    private fun isModelIntent(q: String): Boolean =
        q.contains("модел") || q.contains("нейросет") || q.contains("нейронк") ||
            q.contains("какой ии") || q.contains("какая ии") ||
            q.contains("что лучше") || q.contains("какая лучше") ||
            q.contains("какой лучше") || q.contains("кто лучше") ||
            q.contains("какую выбрать") || q.contains("лучшая модель") ||
            q.contains("лучший") && (q.contains("gpt") || q.contains("claude") ||
            q.contains("gemini") || q.contains("llama") || q.contains("model")) ||
            q.contains("gpt") || q.contains("claude") || q.contains("gemini") ||
            q.contains("llama") || q.contains("mistral") ||
            q.contains("which model") || q.contains("best model") ||
            q.contains("лучшая нейро")

    private fun isGuideIntent(q: String): Boolean =
        q.contains("battle") || q.contains("direct") ||
            q.contains("лидерборд") || q.contains("leaderboard") || q.contains("рейтинг") ||
            q.contains("истори") && q.contains("чат") ||
            q.contains("history") ||
            q.contains("где") || q.contains("как найти") || q.contains("where") ||
            q.contains("что такое") || q.contains("what is") || q.contains("что за") ||
            q.contains("как работает") || q.contains("how does") || q.contains("как устроен") ||
            q.contains("как пользоваться") || q.contains("how to use") ||
            q.contains("раздел") || q.contains("страница") || q.contains("навигац") ||
            q.contains("путеводитель") || q.contains("проведи") ||
            q.contains("расскажи про") || q.contains("объясни") || q.contains("режим") ||
            q.contains("webdev") || q.contains("шаблон") || q.contains("template") ||
            q.contains("совет") || q.contains("tips")

    // ------------------------------------------------------------ домены

    private val CODE = 0
    private val WEBDEV = 1
    private val IMAGE = 2
    private val VIDEO = 3
    private val SEARCH = 4
    private val REASONING = 5
    private val TRANSLATE = 6
    private val SUMMARY = 7
    private val AGENT = 8
    private val WRITING = 9
    private val GENERAL = 10

    private fun detectDomain(q: String): Int {
        if (q.contains("код") || q.contains("программ") || q.contains("разработ") ||
            q.contains("приложение") || q.contains("backend") || q.contains("frontend") ||
            q.contains("python") || q.contains("javascript") || q.contains(" react") ||
            q.contains("sql") || q.contains("баг") || q.contains("ошибк") ||
            q.contains("api") || q.contains("бота") || q.contains("скрипт") ||
            q.contains("kotlin") || q.contains("java") || q.contains("c++") ||
            q.contains("golang") || q.contains("rust") || q.contains("алгоритм") ||
            q.contains("coding") || q.contains("code")
        ) return CODE

        if (q.contains("лендинг") || q.contains("landing") ||
            q.contains("сайт") || q.contains("веб") || q.contains("web") ||
            q.contains("дашборд") || q.contains("dashboard") || q.contains("магазин") ||
            q.contains("портфолио") || q.contains("интерфейс") ||
            q.contains("макет") || q.contains("site")
        ) return WEBDEV

        if (q.contains("картин") || q.contains("изображ") || q.contains("рисун") ||
            q.contains("лого") || q.contains("арт") || q.contains("фото") ||
            q.contains("image") || q.contains("иконк") || q.contains("постер") ||
            q.contains("иллюстрац") || q.contains("аватар") || q.contains("обои")
        ) return IMAGE

        if (q.contains("видео") || q.contains("клип") || q.contains("анимац") ||
            q.contains("мульт") || q.contains("video") || q.contains("ролик") ||
            q.contains("заставк")
        ) return VIDEO

        if (q.contains("новост") || q.contains("актуальн") || q.contains("свеж") ||
            q.contains("факт") || q.contains("поиск") || q.contains("search") ||
            q.contains("сегодня") || q.contains("недавн") || q.contains("погода") ||
            q.contains("курс валют") || q.contains("2025") || q.contains("2026")
        ) return SEARCH

        if (q.contains("матем") || q.contains("логическ") || q.contains("доказат") ||
            q.contains("головоломк") || q.contains("reasoning") ||
            q.contains("уравнен") || q.contains("геометр") || q.contains("алгебр") ||
            q.contains("статистик") || q.contains("math")
        ) return REASONING

        if (q.contains("перевод") || q.contains("переведи") || q.contains("translate") ||
            q.contains("на английск") || q.contains("на русск") || q.contains("на немецк") ||
            q.contains("на испанск") || q.contains("на французск")
        ) return TRANSLATE

        if (q.contains("пересказ") || q.contains("конспект") || q.contains("резюме") ||
            q.contains("сократи") || q.contains("суммар") || q.contains("кратко") ||
            q.contains("самое главное") || q.contains("выжимк") || q.contains("summary")
        ) return SUMMARY

        if (q.contains("агент") || q.contains("автоматиз") || q.contains("автономн") ||
            q.contains("многошагов") || q.contains("план действий") ||
            q.contains("agent") || q.contains("пусть сам")
        ) return AGENT

        if (q.contains("истори") || q.contains("рассказ") || q.contains("стих") ||
            q.contains("сценари") || q.contains("пост") || q.contains("копирайт") ||
            q.contains("письмо") || q.contains("статья") || q.contains("текст") ||
            q.contains("эссе") || q.contains("слоган") || q.contains("назван") ||
            q.contains("заголов") || q.contains("сочинен") || q.contains("блог") ||
            q.contains("твит") || q.contains("поэм") || q.contains("writing") ||
            q.contains("story") || q.contains("article")
        ) return WRITING

        return GENERAL
    }

    private fun cleanTask(raw: String): String {
        var s = raw.trim()
        val prefixes = listOf(
            "составь промпт", "составь запрос", "напиши промпт", "напиши запрос",
            "промпт для", "промпт:", "промпт ", "prompt for", "prompt:",
            "сформулируй промпт", "сформулируй запрос", "сделай промпт",
            "придумай промпт", "составь", "напиши", "придумай",
            "write a prompt", "compose a prompt", "write",
            "какая модель лучше для", "какая модель подойдёт для", "какая модель для",
            "какой ии лучше для", "какая нейросеть для", "какая нейросеть лучше для",
            "что лучше для", "which model is best for", "best model for", "model for"
        )
        val lower = s.lowercase()
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                s = s.substring(p.length).trim().trimStart(':', ' ', '-', '—').trim()
                break
            }
        }
        s = s.trimEnd('.', '!', '?', ' ', '…')
        return if (s.length < 3) {
            t("опишите вашу задачу здесь", "describe your task here")
        } else s
    }

    // ------------------------------------------------------ ответы-советы

    private fun modeLine(domain: Int): String = when (domain) {
        CODE -> t(
            "📍 Режим: Code (в меню сайта). Если нужен целый сайт или приложение — WebDev с шаблоном.",
            "📍 Mode: Code (site menu). For a whole site or app — WebDev with a template."
        )
        WEBDEV -> t(
            "📍 Режим: WebDev — выберите шаблон (Landing page, Dashboard, Fullstack app) и модель соберёт проект целиком.",
            "📍 Mode: WebDev — pick a template (Landing page, Dashboard, Fullstack app) and the model builds the whole project."
        )
        IMAGE -> t(
            "📍 Режим: Image — генерация изображений.",
            "📍 Mode: Image — image generation."
        )
        VIDEO -> t(
            "📍 Режим: Video — генерация видео.",
            "📍 Mode: Video — video generation."
        )
        SEARCH -> t(
            "📍 Режим: Search — модели с веб-поиском (свежие данные).",
            "📍 Mode: Search — models with web access (fresh data)."
        )
        REASONING -> t(
            "📍 Режим: Text; выбирайте reasoning-модели — верх общего лидерборда.",
            "📍 Mode: Text; pick a reasoning model — top of the overall leaderboard."
        )
        TRANSLATE, SUMMARY -> t(
            "📍 Режим: Text (Battle или Direct).",
            "📍 Mode: Text (Battle or Direct)."
        )
        AGENT -> t(
            "📍 Режим: Agent — автономные агенты для многошаговых задач.",
            "📍 Mode: Agent — autonomous agents for multi-step tasks."
        )
        WRITING, GENERAL -> t(
            "📍 Режим: Battle Mode — сравните две модели на одном запросе, или Direct — конкретная модель.",
            "📍 Mode: Battle Mode — compare two models on one prompt, or Direct — a specific model."
        )
        else -> ""
    }

    private fun modelLine(domain: Int): String = when (domain) {
        CODE -> t(
            "🤖 Модель: топ категорий Code и WebDev на лидерборде. Сравните двух лидеров в Battle Mode — так быстрее всего найти лучшую под код.",
            "🤖 Model: top of the Code and WebDev leaderboard categories. Compare two leaders in Battle Mode — the fastest way to find the best one for code."
        )
        WEBDEV -> t(
            "🤖 Модель: категория WebDev лидерборда — модели, которые собирают целые сайты по шаблонам.",
            "🤖 Model: the WebDev leaderboard category — models that build whole sites from templates."
        )
        IMAGE -> t(
            "🤖 Модель: категории Text-to-Image и Image-Edit лидерборда.",
            "🤖 Model: Text-to-Image and Image-Edit leaderboard categories."
        )
        VIDEO -> t(
            "🤖 Модель: категории Text-to-Video и Image-to-Video.",
            "🤖 Model: Text-to-Video and Image-to-Video categories."
        )
        SEARCH -> t(
            "🤖 Модель: те, что работают в режиме Search — у них доступ к свежему интернету.",
            "🤖 Model: models available in Search mode — they have fresh web access."
        )
        REASONING -> t(
            "🤖 Модель: reasoning-модели (многошаговые рассуждения) — верх общего лидерборда.",
            "🤖 Model: reasoning models (step-by-step thinking) — top of the overall leaderboard."
        )
        AGENT -> t(
            "🤖 Модель: категория Agent лидерборда.",
            "🤖 Model: the Agent leaderboard category."
        )
        else -> t(
            "🤖 Модель: топ общего лидерборда; проверьте двух лидеров в Battle Mode.",
            "🤖 Model: top of the overall leaderboard; try two leaders in Battle Mode."
        )
    }

    private fun template(domain: Int, task: String): String {
        val role: String
        val reqs: String
        val format: String
        when (domain) {
            CODE -> {
                role = t("Ты — опытный senior-разработчик", "You are an experienced senior developer")
                reqs = t(
                    "пиши чистый, читаемый код с комментариями; укажи зависимости и как запустить; если задание неоднозначное — задай уточняющий вопрос",
                    "write clean, readable code with comments; list dependencies and how to run it; if the task is ambiguous — ask a clarifying question"
                )
                format = t("в ответе: код + краткое пояснение решения", "reply with: code + a short explanation of the solution")
            }
            WEBDEV -> {
                role = t("Ты — fullstack-разработчик и дизайнер", "You are a fullstack developer and designer")
                reqs = t(
                    "современный минималистичный дизайн, адаптивная вёрстка, рабочий код, который можно сразу запустить",
                    "modern minimalist design, responsive layout, working code that runs immediately"
                )
                format = t("готовый проект + инструкция, как его открыть", "a ready project + instructions on how to open it")
            }
            IMAGE -> {
                role = t("Ты — арт-директор и промпт-инженер", "You are an art director and prompt engineer")
                reqs = t(
                    "опиши стиль, сюжет, свет, композицию и соотношение сторон; добавь детали, влияющие на качество",
                    "describe the style, subject, lighting, composition and aspect ratio; add details that affect quality"
                )
                format = t("готовый промпт для генерации изображения", "a ready image-generation prompt")
            }
            VIDEO -> {
                role = t("Ты — режиссёр и промпт-инженер видеогенерации", "You are a director and video prompt engineer")
                reqs = t(
                    "опиши сцену, движение камеры, длительность, стиль и звук",
                    "describe the scene, camera movement, duration, style and sound"
                )
                format = t("готовый промпт для генерации видео", "a ready video-generation prompt")
            }
            SEARCH -> {
                role = t("Ты — аналитик с доступом к веб-поиску", "You are an analyst with web search access")
                reqs = t(
                    "проверяй актуальность данных и указывай источник информации",
                    "verify the data is current and cite the source"
                )
                format = t("ответ с фактами + источники", "an answer with facts + sources")
            }
            REASONING -> {
                role = t("Ты — математик и логик", "You are a mathematician and logician")
                reqs = t(
                    "рассуждай пошагово, показывай промежуточные выкладки и проверяй решение",
                    "reason step by step, show intermediate steps and verify the solution"
                )
                format = t("пошаговое решение с ответом в конце", "a step-by-step solution with the answer at the end")
            }
            TRANSLATE -> {
                role = t("Ты — профессиональный переводчик", "You are a professional translator")
                reqs = t(
                    "сохрани смысл, тон и стиль оригинала; адаптируй идиомы под язык перевода",
                    "preserve the meaning, tone and style of the original; adapt idioms to the target language"
                )
                format = t("перевод + список сложных мест с пояснениями", "the translation + a list of tricky spots with notes")
            }
            SUMMARY -> {
                role = t("Ты — аналитик, который отлично структурирует информацию", "You are an analyst who structures information well")
                reqs = t(
                    "выдели ключевые тезисы, сохрани факты и цифры, ничего не выдумывай",
                    "extract key points, keep facts and numbers, do not invent anything"
                )
                format = t("краткое резюме + список ключевых пунктов", "a short summary + a bullet list of key points")
            }
            AGENT -> {
                role = t("Ты — автономный агент, который сам планирует и выполняет задачу", "You are an autonomous agent that plans and executes the task")
                reqs = t(
                    "разбей задачу на шаги, выполняй их последовательно, сообщай о прогрессе и проблемах",
                    "split the task into steps, execute them sequentially, report progress and issues"
                )
                format = t("отчёт о выполнении + результат", "an execution report + the result")
            }
            WRITING -> {
                role = t("Ты — профессиональный редактор и копирайтер", "You are a professional editor and copywriter")
                reqs = t(
                    "учитывай аудиторию и тон; пиши структурированно, без воды",
                    "consider the audience and tone; write in a structured way, no fluff"
                )
                format = t("текст с заголовками + 2–3 варианта заголовка", "text with headings + 2–3 title options")
            }
            else -> {
                role = t("Ты — эксперт в нужной области", "You are an expert in the relevant field")
                reqs = t(
                    "будь конкретным, приводи примеры; если что-то неясно — уточни",
                    "be specific, give examples; ask if something is unclear"
                )
                format = t("структурированный ответ", "a well-structured answer")
            }
        }
        return t("«$role. Задача: $task. Требования: $reqs. Формат ответа: $format.»",
            "\"$role. Task: $task. Requirements: $reqs. Output format: $format.\"")
    }

    private fun promptAdvice(domain: Int, task: String): Reply {
        val text = buildString {
            append(modeLine(domain)).append("\n\n")
            append(modelLine(domain)).append("\n\n")
            append(
                t(
                    "Готовый промпт — скопируйте и вставьте:\n\n",
                    "Ready-to-use prompt — copy and paste:\n\n"
                )
            )
            append(template(domain, task)).append("\n\n")
            append(
                t(
                    "💡 Совет: после первого ответа можно уточнять — «короче», " +
                        "«добавь примеры», «перепиши в другом стиле».",
                    "💡 Tip: after the first reply you can refine — \"shorter\", " +
                        "\"add examples\", \"rewrite in a different style\"."
                )
            )
        }
        return Reply(text)
    }

    private fun modelAdvice(domain: Int, task: String): Reply {
        val text = buildString {
            append(modelLine(domain)).append("\n\n")
            append(modeLine(domain)).append("\n\n")
            append(
                t(
                    "Хотите готовый запрос под эту задачу — напишите «составь промпт",
                    "Want a ready prompt for this task? Type \"write a prompt"
                )
            )
            if (task.length > 3 && task != t("опишите вашу задачу здесь", "describe your task here")) {
                append("» — «").append(task).append('"')
            } else {
                append("“")
            }
            append('.')
        }
        return Reply(text)
    }

    // --------------------------------------------------------- путеводитель

    private fun guideBattle(): String = t(
        "⚔️ Battle Mode — визитная карточка arena.ai: вы пишете один запрос, " +
            "две анонимные модели отвечают бок о бок, и вы голосуете за лучший ответ. " +
            "Голоса формируют рейтинг моделей. После голосования можно продолжить " +
            "диалог с любой из них. Идеально, чтобы понять, какая модель сильнее в вашей задаче.",
        "⚔️ Battle Mode is arena.ai's signature feature: you write one prompt, " +
            "two anonymous models answer side by side, and you vote for the best one. " +
            "Votes build the model rankings. After voting you can continue the chat " +
            "with either model. Perfect for finding the strongest model for your task."
    )

    private fun guideDirect(): String = t(
        "🎯 Direct — прямой чат с выбранной моделью, без сравнения и голосования. " +
            "Подходит, когда вы уже знаете, какую модель хотите.",
        "🎯 Direct is a plain chat with a model you pick yourself — no comparison, " +
            "no voting. Use it when you already know which model you want."
    )

    private fun guideLeaderboard(): String = t(
        "🏆 Лидерборд — рейтинги моделей по голосам пользователей. Категории:\n" +
            "• /leaderboard/text — тексты\n" +
            "• /leaderboard/code/webdev — код и сайты\n" +
            "• /leaderboard/agent — агенты\n" +
            "• /leaderboard/vision — зрение\n" +
            "• /leaderboard/document — документы\n" +
            "• /leaderboard/text-to-image — картинки\n" +
            "• /leaderboard/image-edit — редактирование изображений\n" +
            "• /leaderboard/search — поиск\n" +
            "• /leaderboard/text-to-video — видео\n" +
            "Заходите в категорию под вашу задачу и берите лидеров.",
        "🏆 The Leaderboard ranks models by user votes. Categories:\n" +
            "• /leaderboard/text — text\n" +
            "• /leaderboard/code/webdev — code & sites\n" +
            "• /leaderboard/agent — agents\n" +
            "• /leaderboard/vision — vision\n" +
            "• /leaderboard/document — documents\n" +
            "• /leaderboard/text-to-image — images\n" +
            "• /leaderboard/image-edit — image editing\n" +
            "• /leaderboard/search — search\n" +
            "• /leaderboard/text-to-video — video\n" +
            "Open the category for your task and pick the leaders."
    )

    private fun guideHistory(): String = t(
        "🕘 Вся история — в разделе Search (/history/search): ваши прошлые диалоги " +
            "с поиском по ним. Любой чат можно открыть и продолжить с того же места.",
        "🕘 All history lives in Search (/history/search): your past chats with " +
            "a search box. Open any chat and continue right where you left off."
    )

    private fun guideSearch(): String = t(
        "🔎 Режим Search — модели с доступом к свежему веб-поиску. Используйте " +
            "для новостей, актуальных фактов и событий после 2024 года — обычные " +
            "модели таких данных могут не знать.",
        "🔎 Search mode gives models fresh web access. Use it for news, current " +
            "facts and anything after 2024 — regular models may not know it."
    )

    private fun guideWebdev(): String = t(
        "🧩 WebDev — генерация целых проектов по готовым шаблонам: лендинг, " +
            "дашборд, игра, «дизайн → код», fullstack-приложение, интернет-магазин. " +
            "Выбираете шаблон, описываете задачу — и получаете готовый проект, " +
            "который можно посмотреть в браузере и скачать.",
        "🧩 WebDev generates whole projects from templates: landing page, dashboard, " +
            "game, design-to-code, fullstack app, storefront. Pick a template, describe " +
            "your task — and get a working project you can preview and download."
    )

    private fun guideHow(): String = t(
        "🚀 За 5 минут:\n" +
            "1. Главная — Battle Mode (сравнение двух моделей) или Direct (одна модель).\n" +
            "2. Слева в меню: New Chat, Leaderboard, Search.\n" +
            "3. Под задачу выбирайте режим: Code — код, WebDev — сайты, Image — картинки, " +
            "Search — свежие факты, Video — видео.\n" +
            "4. Пишите конкретно: роль, задача, требования, формат (я помогу составить).\n" +
            "⚠️ Важно: чаты могут попасть в публичный датасет — не отправляйте личные данные.",
        "🚀 In 5 minutes:\n" +
            "1. Home is Battle Mode (compare two models) or Direct (one model).\n" +
            "2. Left menu: New Chat, Leaderboard, Search.\n" +
            "3. Match the mode to the task: Code for code, WebDev for sites, Image for " +
            "pictures, Search for fresh facts, Video for videos.\n" +
            "4. Be specific: role, task, requirements, format (I'll help you compose).\n" +
            "⚠️ Note: chats may become part of a public dataset — never send personal data."
    )

    private fun guidePromptTips(): String = t(
        "✍️ 6 правил хорошего промпта:\n" +
            "1. Роль: «Ты — опытный разработчик».\n" +
            "2. Задача: одна и конкретная.\n" +
            "3. Контекст: что дано, что уже пробовали.\n" +
            "4. Требования: ограничения и пожелания.\n" +
            "5. Формат: как должен выглядеть ответ.\n" +
            "6. Примеры: покажите образец, если есть.\n" +
            "Бонус: добавьте «если что-то неясно — задай вопрос».",
        "✍️ 6 rules of a good prompt:\n" +
            "1. Role: \"You are an experienced developer\".\n" +
            "2. Task: one specific task.\n" +
            "3. Context: what's given, what you've tried.\n" +
            "4. Requirements: constraints and wishes.\n" +
            "5. Format: how the answer should look.\n" +
            "6. Examples: show a sample if you have one.\n" +
            "Bonus: add \"ask if anything is unclear\"."
    )

    private fun guideAnswer(q: String): Reply {
        return when {
            q.contains("battle") -> Reply(guideBattle(), listOf(t("Составь промпт", "Write a prompt")))
            q.contains("direct") -> Reply(guideDirect())
            q.contains("лидерборд") || q.contains("leaderboard") || q.contains("рейтинг") ->
                Reply(guideLeaderboard())
            q.contains("истори") || q.contains("history") -> Reply(guideHistory())
            q.contains("webdev") || q.contains("шаблон") || q.contains("template") ||
                q.contains("лендинг") && (q.contains("где") || q.contains("как")) ->
                Reply(guideWebdev())
            q.contains("поиск") || q.contains("search") -> Reply(guideSearch())
            q.contains("промпт") || q.contains("prompt") || q.contains("совет") || q.contains("tips") ->
                Reply(guidePromptTips())
            else -> Reply(guideHow())
        }
    }
}
