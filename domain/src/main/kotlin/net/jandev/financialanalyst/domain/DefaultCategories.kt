package net.jandev.financialanalyst.domain

/** Vorlage für eine mitgelieferte Kategorie samt Schlagwörtern für die automatische Zuordnung. */
data class CategoryTemplate(
    val key: String,
    val name: String,
    val kind: CategoryKind,
    /** Kleingeschriebene Teilstrings, die in Gegenpartei oder Verwendungszweck gesucht werden. */
    val keywords: List<String> = emptyList(),
)

object DefaultCategories {
    const val KEY_TRANSFER = "transfer"
    const val KEY_OTHER_EXPENSE = "other_expense"
    const val KEY_OTHER_INCOME = "other_income"

    val all: List<CategoryTemplate> = listOf(
        CategoryTemplate(
            "groceries", "Lebensmittel", CategoryKind.EXPENSE,
            listOf(
                "rewe", "lidl", "aldi", "edeka", "netto", "penny", "kaufland", "norma", "tegut", "globus",
                "denns", "alnatura", "nahkauf", "marktkauf", "famila", "hit markt", "bäckerei", "baeckerei", "metzgerei",
                "flink", "picnic",
            ),
        ),
        CategoryTemplate(
            "restaurant", "Restaurant & Lieferdienst", CategoryKind.EXPENSE,
            listOf(
                "lieferando", "wolt", "uber eats", "mcdonald", "burger king", "kfc", "subway", "starbucks",
                "restaurant", "pizzeria", "pizza", "döner", "doener", "sushi", "cafe ", "café", "imbiss", "bistro", "dominos",
            ),
        ),
        CategoryTemplate(
            "housing", "Wohnen & Miete", CategoryKind.EXPENSE,
            listOf("miete", "kaltmiete", "warmmiete", "hausverwaltung", "wohnungsbau", "vonovia", "hausgeld", "ikea", "obi ", "bauhaus", "hornbach"),
        ),
        CategoryTemplate(
            "utilities", "Nebenkosten & Energie", CategoryKind.EXPENSE,
            listOf(
                "stadtwerke", "strom", "eon ", "e.on", "vattenfall", "enbw", "rwe", "gasag", "abschlag", "nebenkosten",
                "rundfunk", "beitragsservice", "abfall", "wasserwerk", "octopus energy", "tibber", "yello",
            ),
        ),
        CategoryTemplate(
            "car", "Auto & Tanken", CategoryKind.EXPENSE,
            listOf(
                "aral", "shell", "esso", "total energies", "totalenergies", "jet tank", "tankstelle", "star tank", "avia", "agip",
                "hem tank", "kfz", "werkstatt", "atu ", "a.t.u", "tüv", "tuev", "dekra", "parkhaus", "parken", "easypark",
                "ionity", "enbw mobility",
            ),
        ),
        CategoryTemplate(
            "transit", "ÖPNV & Reisen", CategoryKind.EXPENSE,
            listOf(
                "deutsche bahn", "db vertrieb", "db fernverkehr", "bahn.de", "flixbus", "flixtrain", "deutschlandticket",
                "verkehrsbetriebe", "bvg", "mvg", "hvv", "kvb", "rmv", "vrr", "lufthansa", "ryanair", "eurowings", "easyjet",
                "booking.com", "airbnb", "hotel", "uber ", "bolt", "free now", "tier mobility", "sixt", "europcar",
            ),
        ),
        CategoryTemplate(
            "insurance", "Versicherungen", CategoryKind.EXPENSE,
            listOf(
                "versicherung", "allianz", "huk", "ergo", "axa", "debeka", "generali", "signal iduna", "gothaer",
                "krankenkasse", "techniker krankenkasse", "aok", "barmer", "dak", "haftpflicht", "getsafe", "wgv", "cosmos",
            ),
        ),
        CategoryTemplate(
            "subscriptions", "Abos & Medien", CategoryKind.EXPENSE,
            listOf(
                "netflix", "spotify", "disney", "amazon prime", "prime video", "youtube", "apple.com/bill", "google play",
                "audible", "dazn", "sky ", "wow tv", "rtl+", "joyn", "icloud", "microsoft", "adobe", "openai", "anthropic",
                "github", "dropbox", "nintendo", "playstation", "steam", "xbox", "kindle", "zeit online", "spiegel",
            ),
        ),
        CategoryTemplate(
            "telecom", "Telefon & Internet", CategoryKind.EXPENSE,
            listOf(
                "telekom", "vodafone", "o2 ", "telefonica", "1&1", "1und1", "congstar", "aldi talk", "freenet", "klarmobil",
                "drillisch", "winsim", "fraenk", "pyur", "unitymedia", "netcologne",
            ),
        ),
        CategoryTemplate(
            "shopping", "Shopping", CategoryKind.EXPENSE,
            listOf(
                "amazon", "amzn", "zalando", "otto ", "ebay", "kleinanzeigen", "mediamarkt", "media markt", "saturn",
                "h&m", "h & m", "zara", "c&a", "decathlon", "tchibo", "about you", "temu", "shein", "aliexpress", "etsy",
                "thalia", "galeria", "primark", "uniqlo", "action ", "tedi", "woolworth",
            ),
        ),
        CategoryTemplate(
            "health", "Gesundheit & Drogerie", CategoryKind.EXPENSE,
            listOf(
                "apotheke", "dm-drogerie", "dm drogerie", "rossmann", "müller drogerie", "mueller drogerie", "arzt", "praxis",
                "zahnarzt", "optiker", "fielmann", "apollo optik", "physio", "klinik", "docmorris", "shop apotheke", "friseur",
            ),
        ),
        CategoryTemplate(
            "leisure", "Freizeit & Sport", CategoryKind.EXPENSE,
            listOf(
                "fitness", "mcfit", "fitx", "urban sports", "clever fit", "kino", "cinemaxx", "cinestar", "uci ", "eventim",
                "ticketmaster", "theater", "museum", "schwimmbad", "verein", "konzert", "bowling", "zoo",
            ),
        ),
        CategoryTemplate(
            "education", "Bildung", CategoryKind.EXPENSE,
            listOf("universität", "universitaet", "hochschule", "semesterbeitrag", "studierendenwerk", "volkshochschule", "vhs ", "udemy", "coursera", "duolingo", "kita", "schule"),
        ),
        CategoryTemplate(
            "loans", "Kredite & Zinsen", CategoryKind.EXPENSE,
            listOf("darlehen", "kreditrate", "tilgung", "ratenkredit", "sollzinsen", "dispozins", "kfw", "bafög", "bafoeg", "klarna", "ratenzahlung", "santander consumer", "targobank"),
        ),
        CategoryTemplate(
            "fees", "Gebühren & Steuern", CategoryKind.EXPENSE,
            listOf("kontoführung", "kontofuehrung", "entgelt", "gebühr", "gebuehr", "finanzamt", "steuer", "bundeskasse", "zoll", "bußgeld", "bussgeld", "jahresbeitrag karte"),
        ),
        CategoryTemplate(
            "cash", "Bargeld", CategoryKind.EXPENSE,
            listOf("geldautomat", "bargeldauszahlung", "auszahlung ga", "atm ", "cash withdrawal", "bargeld"),
        ),
        CategoryTemplate(
            "saving", "Sparen & Investieren", CategoryKind.EXPENSE,
            listOf("sparplan", "wertpapier", "depot", "scalable", "etf", "bausparen", "bausparkasse", "savings plan", "wertpapierkauf"),
        ),
        CategoryTemplate(KEY_OTHER_EXPENSE, "Sonstige Ausgaben", CategoryKind.EXPENSE),

        CategoryTemplate(
            "salary", "Gehalt & Lohn", CategoryKind.INCOME,
            listOf("gehalt", "lohn", "bezüge", "bezuege", "entgeltabrechnung", "salary", "vergütung", "verguetung", "besoldung"),
        ),
        CategoryTemplate(
            "benefits", "Staatliche Leistungen", CategoryKind.INCOME,
            listOf("kindergeld", "familienkasse", "elterngeld", "wohngeld", "arbeitsagentur", "bundesagentur", "rente", "rentenversicherung", "bürgergeld"),
        ),
        CategoryTemplate(
            "refunds", "Erstattungen", CategoryKind.INCOME,
            listOf("erstattung", "rückzahlung", "rueckzahlung", "gutschrift", "refund", "retoure", "steuererstattung"),
        ),
        CategoryTemplate(
            "capital", "Zinsen & Kapitalerträge", CategoryKind.INCOME,
            listOf("zinsen", "dividende", "habenzins", "interest", "kapitalertrag", "ausschüttung", "ausschuettung", "saveback", "cashback"),
        ),
        CategoryTemplate(KEY_OTHER_INCOME, "Sonstige Einnahmen", CategoryKind.INCOME),

        CategoryTemplate(
            KEY_TRANSFER, "Umbuchung", CategoryKind.TRANSFER,
            listOf("umbuchung", "übertrag", "uebertrag", "eigenes konto", "kreditkartenabrechnung", "ausgleich kreditkarte"),
        ),
    )
}
