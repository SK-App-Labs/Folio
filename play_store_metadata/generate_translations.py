# -*- coding: utf-8 -*-
import os
import json
import csv

translations = {}

# 1. English (Base)
translations['en-US'] = {
    'lang_name': 'English (United States)',
    'app_name': 'Folio: Expense & Budget Ledger',
    'short_description': 'Offline expense tracker & budget ledger. Zero ads, Zero paywalls, 100% Private.',
    'full_description': """Folio is a 100% offline money manager, expense tracker, and double-entry budget planner built for absolute financial clarity and total privacy.

Unlike apps that read your SMS, sell your data, or charge monthly subscriptions, Folio stores your records strictly on your phone’s local SQLite database—zero ads, zero cloud servers, zero internet permissions, and zero paywalls.

🛡️ 100% FREE, PRIVATE & UNLOCKED
• 100% Ad-Free (No Ads): Zero pop-ups, banners, or sponsored clutter.
• No Paywalls or Subscriptions: Every core feature is fully unlocked.
• 100% Offline: Zero internet permission. Your data never leaves your phone.
• No Account Required: No email or login needed. Start tracking instantly.

CORE CAPABILITIES:

• 5-View Home Ledger & Spending Tracker
Audit cashflow across five dedicated perspectives:
- Daily: Chronological receipt feed with daily income, expense, and net totals.
- Calendar: Monthly calendar grid with visual cashflow dots. Tap any day to log or inspect.
- Weekly: Clustered weekly burn-rate buckets across your financial month.
- Monthly: 12-month annual cashflow overview with expandable weekly drill-downs.
- Summary: Savings Rate %, carry-over balance, top categories, and 1-tap CSV export.

• Credit Cards Cockpit & Net Worth Ledger
Manage Cash, Bank Accounts, Savings, Investments, Loans, and Credit Cards in one cockpit:
- Credit Card Intelligence: Automatically separates Statement Due from Unbilled Spend.
- Smart Swipe Advisor: Recommends which card to swipe to maximize interest-free grace periods.
- 30% Bureau Utilization Meter: Proactive gauges keep credit utilization below warning limits.
- 1-Tap Bill Settlement: Settle credit card bills from your bank without double-counting expenses.
- Bank Passbooks & Reconciliation: Audit running balances and reconcile in 1 tap.
- Linked Debit Cards: Deduct swipes automatically from the parent bank while isolating spend.
- Installment Plans (EMI): Split credit card purchases into multi-month payment schedules.
- Investment Portfolios: Track invested principal vs. market valuations with return analytics.

• Forward-Looking Budgets & Wealth Vaults
- Safe-to-Spend Today: Discretionary daily cash by ring-fencing upcoming 14-day bills and debts.
- Pacing Ticks: Visual needles show whether your burn rate is ahead of or behind the calendar day.
- Wealth Vaults: Double-entry savings target tracking linked directly to real asset accounts.
- Comparative Deltas: Micro-badges showing spending trends vs. last week, month, or year.

• Advanced Financial Entry & Reminders
- Payee Autocomplete: Instant predictive autofill for accounts, categories, and tags.
- Split Transactions: Divide a single bill across multiple categories down to the cent or paisa.
- Fast Math Keypad: Live numeric calculation (+, −, ×, ÷) with launcher quick-log shortcuts.
- Recurring Commitments: Schedule rules (Daily, Weekly, Monthly) with due date reminders.
- Refunds & Returns: Offset category spend accurately without falsely inflating income.

• Regional & Visual Customization
- Global Localization: Translated into 13 languages with Android 13+ Per-App Language support.
- Universal Currencies: Supports 170+ currencies with offline custom exchange rates.
- 4 Icon Pack Styles: Choose between Material Classic, Phosphor, Lucide, and Expressive Emoji.
- Theming: 6 Theme Modes (System, Light, Dark, AMOLED Black, Sepia, Slate Gray) with 12 Accent Palettes.
- Number Formatting: Switch between Indian (12,34,567.89) and International (1,234,567.89) formats.

• Ironclad Privacy, Security & Data Ownership
- Zero Internet Permission: The app cannot access the internet under any circumstances.
- Biometric App Lock: Secure records with Fingerprint, Face Unlock, or Device PIN.
- Offline PDF Statements: Generate Skia-rendered account statements and tax reports on-device.
- Portable Backups: Automated rolling backups, portable ZIP archives, and spreadsheet CSV exports."""
}

# 2. German (de-DE)
translations['de-DE'] = {
    'lang_name': 'German (Deutsch)',
    'app_name': 'Folio: Haushaltsbuch & Budget',
    'short_description': 'Offline-Haushaltsbuch & Budget. Keine Werbung, keine Paywalls, 100% privat.',
    'full_description': """Folio ist ein 100% offline funktionierender persönlicher Finanzmanager, Haushaltsbuch und doppeltes Buchführungs-Hauptbuch für absolute finanzielle Klarheit und vollkommene Privatsphäre.

Im Gegensatz zu Apps, die Ihre SMS lesen, Ihre Daten verkaufen oder monatliche Abonnements verlangen, speichert Folio alle Einträge ausschließlich in der lokalen SQLite-Datenbank Ihres Smartphones – null Werbung, null Cloud-Server, null Internet-Berechtigungen und null Bezahlschranken.

🛡️ 100% KOSTENLOS, PRIVAT & ENTSPERRT
• Keine Werbung: Keine Pop-ups, Banner oder gesponserte Ablenkungen.
• Keine Paywalls oder Abos: Alle Kernfunktionen sind dauerhaft voll freigeschaltet.
• 100% Offline: Keine Internetberechtigung. Ihre Finanzdaten verlassen niemals Ihr Gerät.
• Kein Konto erforderlich: Keine Registrierung oder E-Mail nötig. Direkt loslegen.

KERNFUNKTIONEN:

• Hauptbuch mit 5 Ansichten
Überwachen Sie Ihren Cashflow aus fünf spezialisierten Perspektiven:
- Täglich: Chronologischer Beleg-Feed mit täglichen Einnahmen, Ausgaben und Salden.
- Kalender: Monatskalender mit visuellen Cashflow-Punkten. Antippen zum Prüfen oder Buchen.
- Wöchentlich: Geordnete wöchentliche Ausgaben-Cluster über Ihren Abrechnungsmonat.
- Monatlich: 12-Monats-Jahresüberblick mit aufklappbaren wöchentlichen Detailanalysen.
- Zusammenfassung: Sparquote %, Übertragssaldo, Top-Kategorien und 1-Tap-CSV-Export.

• Kreditkarten-Cockpit & Nettovermögen
Verwalten Sie Bargeld, Bankkonten, Sparkonten, Depots, Kredite und Kreditkarten an einem Ort:
- Kreditkarten-Intelligenz: Trennt automatisch fällige Abrechnungsbeträge von laufenden Ausgaben.
- Smart Swipe Berater: Empfiehlt die optimale Karte zur Maximierung zinsfreier Zahlungsziele.
- 30%-Auslastungsanzeige: Proaktive Warnanzeigen halten Ihre Kreditauslastung im sicheren Bereich.
- 1-Tap-Rechnungsausgleich: Begleichen Sie Kartenabrechnungen ohne doppelte Erfassung.
- Bank-Passbuch & Abgleich: Laufende Salden prüfen und mit einem Fingertipp abstimmen.
- Verknüpfte Debitkarten: Bucht Ausgaben vom Girokonto ab und isoliert Kartenausgaben.
- Anlageportfolios: Erfasst investiertes Kapital vs. Marktwert samt Renditeanalyse.

• Vorausschauende Budgets & Spartresore
- Heute verfügbar: Berechnet frei verfügbares Tagesbudget abzüglich anstehender 14-Tage-Fixkosten.
- Pacing-Indikatoren: Zeigen visuell an, ob Ihr Ausgabentempo dem Kalendertag voraus ist.
- Spartresore: Doppelte Buchführung für Sparziele, direkt mit echten Anlagekonten verknüpft.
- Vergleichs-Deltas: Trend-Badges für Ausgaben im Vergleich zur Vorwoche, Vormonat oder Vorjahr.

• Schnelle Buchung & Fälligkeitserinnerungen
- Schnelles Zahlenfeld mit Live-Rechenfunktionen (+, −, ×, ÷).
- Split-Buchungen: Teilen Sie einen Betrag centgenau auf mehrere Kategorien auf.
- Daueraufträge: Flexible Wiederholungsregeln (Täglich, Wöchentlich, Monatlich, Monatsende).
- Zahlungserinnerungen: Lokale Benachrichtigungen für fällige Rechnungen und Kreditkarten.
- Startbildschirm-Shortcuts: 1-Tap-Verknüpfungen für Ausgaben, Einnahmen und Umbuchungen.
- Rückerstattungen: Gleicht Ausgaben exakt aus, ohne Einnahmen künstlich aufzublähen.

• Regionale & Visuelle Anpassung
- Lokalisierung: Übersetzt in 13 Sprachen mit nativer Android 13+ App-Sprachauswahl.
- Globale Währungen: Unterstützt 170+ Währungen mit benutzerdefinierten Offline-Wechselkursen.
- 4 Symbol-Pakete: Material Classic, Phosphor, Lucide und ausdrucksstarke Emojis.
- Design: 4 Modi (System, Hell, Dunkel, AMOLED Schwarz) mit 12 Akzentfarben.
- Zahlenformate: Umschaltbar zwischen internationalem und regionalem Format.

• Absolute Datensicherheit & Privatsphäre
- Keine Internetberechtigung: Die App kann technisch niemals eine Verbindung aufbauen.
- Biometrische App-Sperre: Schutz durch Fingerabdruck, Gesichtserkennung oder Geräte-PIN.
- Offline-PDF-Auszüge: Generiert hochwertige Kontoauszüge und Steuerberichte direkt auf dem Gerät.
- Portabler Export: Automatische rollierende Backups, ZIP-Archive und Tabellen-CSV-Exporte."""
}

# 3. Spanish (es-ES / es-419)
translations['es-ES'] = {
    'lang_name': 'Spanish (Español)',
    'app_name': 'Folio: Gastos y Presupuesto',
    'short_description': 'Gestor de gastos y presupuesto offline. Sin anuncios, sin pagos, 100% privado.',
    'full_description': """Folio es un gestor de finanzas personales, rastreador de gastos y libro mayor de partida doble 100% offline, diseñado para brindarte total claridad y máxima privacidad.

A diferencia de las apps que leen tus SMS, venden tus datos o cobran suscripciones mensuales, Folio guarda tus registros únicamente en la base de datos local SQLite de tu teléfono: sin anuncios, sin servidores en la nube, sin permisos de internet y sin muros de pago.

🛡️ 100% GRATIS, PRIVADO Y DESBLOQUEADO
• Cero Anuncios: Sin ventanas emergentes, banners ni publicidad.
• Sin Suscripciones: Todas las funciones clave están totalmente desbloqueadas.
• 100% Fuera de Línea: Cero permisos de internet. Tus datos nunca salen de tu dispositivo.
• Sin Registro: No requiere correo ni cuenta. Comienza a usarlo al instante.

CAPACIDADES PRINCIPALES:

• Libro Mayor en 5 Vistas
Supervisa tu flujo de caja desde cinco perspectivas dedicadas:
- Diario: Flujo cronológico con ingresos, gastos y saldo neto diario.
- Calendario: Vista mensual con indicadores de flujo. Toca cualquier día para consultar o registrar.
- Semanal: Bloques semanales agrupados para vigilar tu ritmo de gasto del mes.
- Mensual: Resumen anual de 12 meses con desgloses semanales expandibles.
- Resumen: Tasa de ahorro %, saldo acumulado, categorías principales y exportación CSV en 1 toque.

• Tarjetas de Crédito y Patrimonio
Gestiona Efectivo, Cuentas Bancarias, Ahorros, Inversiones, Préstamos y Tarjetas de Crédito:
- Inteligencia en Tarjetas: Separa automáticamente el saldo al corte del saldo flotante.
- Asesor Inteligente: Recomienda qué tarjeta usar para maximizar días sin intereses.
- Medidor de Utilización 30%: Gráficos proactivos para mantener el uso de crédito en niveles seguros.
- Pago de Tarjeta en 1 Toque: Liquida deudas desde tu banco sin duplicar gastos.
- Libreta Bancaria y Conciliación: Revisa saldos continuos y concilia movimientos en 1 toque.
- Tarjetas de Débito Vinculadas: Descuenta de la cuenta bancaria aislando el consumo con tarjeta.
- Portafolios de Inversión: Registra capital aportado vs. valor de mercado con rentabilidad.

• Presupuestos y Bóvedas de Ahorro
- Disponible Hoy (Safe-to-Spend): Dinero diario libre reteniendo pagos programados a 14 días.
- Medidores de Ritmo: Punteros visuales que indican si tu gasto va por delante o detrás del calendario.
- Bóvedas de Ahorro: Metas de ahorro con partida doble conectadas a tus cuentas de activos reales.
- Comparativas: Indicadores de tendencias de gasto respecto a la semana, mes o año anterior.

• Registro Rápido y Recordatorios
- Teclado numérico ágil con calculadora en tiempo real (+, −, ×, ÷).
- Gastos Divididos: Reparte un solo gasto entre varias categorías al centavo exacto.
- Gastos Recurrentes: Reglas programables (Diario, Semanal, Mensual, Fin de Mes).
- Recordatorios de Vencimiento: Alertas locales para facturas y fechas límite de tarjetas.
- Accesos Directos: Atajos en el lanzador para registrar Gastos, Ingresos o Transferencias en 1 toque.
- Devoluciones y Reembolsos: Ajusta gastos de categoría sin inflar falsamente los ingresos.

• Personalización Visual y Regional
- Localización Global: Traducido a 13 idiomas con soporte de idioma por aplicación en Android 13+.
- Monedas Mundiales: Soporta más de 170 monedas con tasas de cambio manuales offline.
- 4 Paquetes de Iconos: Material Classic, Phosphor, Lucide y Emojis.
- Temas: 4 modos (Sistema, Claro, Oscuro, Negro AMOLED) con 12 paletas de acento.
- Formatos de Número: Alterna entre formato estándar e internacional.

• Privacidad y Seguridad Total
- Sin Permiso de Internet: La aplicación no tiene acceso físico ni técnico a la red.
- Bloqueo Biométrico: Protege tus finanzas con huella dactilar, reconocimiento facial o PIN.
- Extractos en PDF Offline: Genera estados de cuenta y reportes fiscales en tu dispositivo.
- Respaldos Portátiles: Copias automáticas, archivos ZIP y exportación a CSV."""
}

# 4. French (fr-FR)
translations['fr-FR'] = {
    'lang_name': 'French (Français)',
    'app_name': 'Folio: Budget et Dépenses',
    'short_description': 'Suivi des dépenses et budget 100% hors ligne. Zéro pub, sans abonnement, privé.',
    'full_description': """Folio est un gestionnaire de finances personnelles, suivi de dépenses et grand livre à partie double 100% hors ligne, conçu pour une clarté totale et une confidentialité absolue.

Contrairement aux apps qui lisent vos SMS, revendent vos données ou imposent des abonnements mensuels, Folio conserve vos comptes dans la base SQLite locale de votre téléphone : zéro publicité, aucun serveur cloud, aucune autorisation internet et aucun mur payant.

🛡️ 100% GRATUIT, PRIVÉ & DÉVERROUILLÉ
• Zéro Publicité : Aucun bandeau, pop-up ou contenu sponsorisé.
• Sans Abonnement : Toutes les fonctionnalités clés sont totalement débloquées.
• 100% Hors Ligne : Zéro autorisation internet. Vos données restent sur l'appareil.
• Aucun Compte Requis : Ni e-mail ni connexion nécessaire. Prêt à l'emploi.

FONCTIONNALITÉS ESSENTIELLES :

• Grand Livre en 5 Vues
Visualisez vos flux de trésorerie sous cinq angles dédiés :
- Quotidien : Reçus chronologiques avec revenus, dépenses et solde net du jour.
- Calendrier : Grille mensuelle avec pastilles de trésorerie. Touchez pour consulter ou saisir.
- Hebdomadaire : Regroupements par semaine pour réguler votre rythme mensuel.
- Mensuel : Vue d'ensemble sur 12 mois avec détails hebdomadaires extensibles.
- Synthèse : Taux d'épargne %, report à nouveau, catégories et export CSV en 1 clic.

• Cartes de Crédit & Patrimoine Net
Gérez Espèces, Comptes Bancaires, Épargne, Placements, Emprunts et Cartes de Crédit :
- Cartes de Crédit : Sépare le relevé dû des dépenses courantes automatiquement.
- Conseiller de Paiement : Recommande la carte optimale pour maximiser la période sans intérêt.
- Jauge d'Utilisation 30% : Alertes proactives pour garder l'utilisation du crédit sous contrôle.
- Règlement en 1 Clic : Réglez vos relevés depuis votre banque sans doubler les dépenses.
- Livret & Rapprochement : Suivez le solde continu et effectuez vos rapprochements en 1 clic.
- Cartes Débit Liées : Débite le compte principal en isolant les achats carte.
- Portefeuille d'Investissement : Suivez le capital face aux valorisations avec rentabilité.

• Budgets Prévisionnels & Coffres d'Épargne
- Reste à Dépenser Aujourd'hui : Calcule l'argent disponible en sécurisant les factures à 14 jours.
- Indicateurs d'Allure : Aiguilles visuelles montrant si les dépenses devancent le calendrier.
- Coffres d'Épargne : Suivi d'objectifs en partie double relié à vos comptes réels.
- Deltas Comparatifs : Badges de tendances par rapport au mois ou à l'année précédente.

• Saisie Rapide & Rappels d'Échéances
- Pavé numérique rapide avec calcul en direct (+, −, ×, ÷).
- Dépenses Fractionnées : Répartissez une dépense entre plusieurs catégories au centime près.
- Opérations Récurrentes : Programmation flexible (Quotidien, Hebdomadaire, Mensuel, Fin de mois).
- Rappels d'Échéances : Notifications locales pour vos factures et cartes bancaires.
- Raccourcis Accueil : Accès direct pour enregistrer Dépense, Revenu ou Virement en 1 geste.
- Remboursements : Ajustez les dépenses par catégorie sans fausser vos revenus.

• Personnalisation Visuelle & Régionale
- Traduction Complète : Disponible en 13 langues avec support par app Android 13+.
- Devises Mondiales : Prend en charge 170+ devises avec taux de change manuels hors ligne.
- 4 Styles d'Icônes : Material Classic, Phosphor, Lucide et Emojis expressifs.
- Thèmes : 4 modes (Système, Clair, Sombre, Noir AMOLED) et 12 palettes de couleurs.
- Formats de Nombres : Prise en charge des formats régionaux et internationaux.

• Confidentialité et Sécurité Absolues
- Aucune Autorisation Internet : L'application n'a aucun accès physique ou technique au réseau.
- Verrouillage Biométrique : Empreinte digitale, reconnaissance faciale ou code PIN.
- Relevés PDF Hors Ligne : États de comptes et rapports fiscaux générés sur l'appareil.
- Sauvegardes Mobiles : Sauvegardes continues, archives ZIP et exports CSV."""
}

# 5. Hindi (hi-IN)
translations['hi-IN'] = {
    'lang_name': 'Hindi (हिन्दी)',
    'app_name': 'Folio: खर्च ट्रैकर व बजट',
    'short_description': 'ऑफ़लाइन खर्च ट्रैकर व बजट बही। शून्य विज्ञापन, कोई भुगतान नहीं, 100% निजी।',
    'full_description': """फ़ोलियो (Folio) एक 100% ऑफ़लाइन व्यक्तिगत वित्त प्रबंधक, खर्च ट्रैकर और दोहरी-प्रविष्टि खाता बही (Double-entry Ledger) है, जिसे पूर्ण वित्तीय स्पष्टता और संपूर्ण गोपनीयता के लिए बनाया गया है।

उन ऐप्स के विपरीत जो आपके एसएमएस पढ़ते हैं, डेटा बेचते हैं या मासिक सदस्यता शुल्क लेते हैं, फ़ोलियो आपका पूरा वित्तीय रिकॉर्ड केवल आपके फ़ोन के स्थानीय SQLite डेटाबेस में रखता है—शून्य विज्ञापन, कोई क्लाउड सर्वर नहीं, शून्य इंटरनेट अनुमति और कोई छिपे शुल्क नहीं।

🛡️ 100% मुफ़्त, सुरक्षित और अनलॉक्ड
• शून्य विज्ञापन: कोई पॉप-अप, बैनर या प्रायोजित विज्ञापन नहीं।
• कोई सब्सक्रिप्शन या पेवॉल नहीं: सभी मुख्य सुविधाएं हमेशा पूरी तरह अनलॉक हैं।
• 100% ऑफ़लाइन: शून्य इंटरनेट अनुमति। आपका डेटा कभी भी आपके फ़ोन से बाहर नहीं जाता।
• किसी खाते की आवश्यकता नहीं: कोई ईमेल या लॉगिन नहीं। तुरंत उपयोग शुरू करें।

मुख्य विशेषताएं:

• 5-व्यू होम लेजर (खाता बही)
पाँच समर्पित दृष्टिकोणों से अपने कैशफ़्लो की समीक्षा करें:
- दैनिक (Daily): दैनिक आय, व्यय और शुद्ध शेष के साथ कालक्रमानुसार रसीद फ़ीड।
- कैलेंडर (Calendar): विज़ुअल कैशफ़्लो डॉट्स वाला मासिक ग्रिड। प्रविष्टि देखने या जोड़ने के लिए किसी भी दिन टैप करें।
- साप्ताहिक (Weekly): आपके वित्तीय महीने के अनुसार साप्ताहिक खर्च की गति।
- मासिक (Monthly): विस्तृत साप्ताहिक विवरण के साथ 12 महीने का वार्षिक वित्तीय अवलोकन।
- सारांश (Summary): बचत दर %, संचित शेष, शीर्ष श्रेणियां और 1-टैप सीएसवी (CSV) निर्यात।

• क्रेडिट कार्ड कॉकपिट और कुल संपत्ति
कैश, बैंक खाते, बचत, निवेश, ऋण और क्रेडिट कार्ड को एक ही स्थान पर प्रबंधित करें:
- क्रेडिट कार्ड इंटेलिजेंस: बिल किए गए देय बैलेंस और अनबिल्ड खर्च को स्वचालित रूप से अलग करता है।
- स्मार्ट स्वाइप सलाहकार: ब्याज-मुक्त अवधि का अधिकतम लाभ उठाने के लिए सही कार्ड का सुझाव देता है।
- 30% क्रेडिट उपयोगिता मीटर: आपके क्रेडिट उपयोग को सुरक्षित सीमा में रखने के लिए सतर्क करता है।
- 1-टैप बिल भुगतान: खर्चों की दोबारा गिनती किए बिना बैंक से क्रेडिट कार्ड बिल का भुगतान करें।
- बैंक पासबुक और समाधान: चालू बैलेंस का ऑडिट करें और 1 टैप में समाधान (Reconcile) करें।
- लिंक्ड डेबिट कार्ड: कार्ड खर्च को अलग रखते हुए बैंक खाते से सीधे राशि घटाता है।
- निवेश पोर्टफोलियो: लाभ-हानि विश्लेषण के साथ निवेशित मूलधन बनाम बाज़ार मूल्य ट्रैक करें।

• अग्रिम बजट और वेल्थ वॉल्ट्स (बचत तिजोरी)
- आज खर्च करने योग्य राशि (Safe-to-Spend): आगामी 14 दिनों के बिलों को सुरक्षित रखकर दैनिक सुरक्षित बजट बताता है।
- पेसिंग संकेतक: विज़ुअल सुई दिखाती है कि आपकी खर्च की गति तय बजट के आगे है या पीछे।
- वेल्थ वॉल्ट्स: आपके वास्तविक बैंक खातों से जुड़ी दोहरी-प्रविष्टि बचत लक्ष्य ट्रैकिंग।
- तुलनात्मक विश्लेषण: पिछले सप्ताह, महीने या वर्ष की तुलना में खर्च के रुझान दिखाने वाले बैज।

• त्वरित प्रविष्टि और देय अनुस्मारक
- लाइव गणित गणना (+, −, ×, ÷) के साथ तेज़ न्यूमेरिक कीपैड।
- स्प्लिट ट्रांज़ैक्शन: एक बिल को कई श्रेणियों में पैसे-पैसे तक विभाजित करें।
- आवर्ती प्रतिबद्धताएं (Recurring): दैनिक, साप्ताहिक, मासिक या महीने के अंत के नियम शेड्यूल करें।
- देय तिथि अनुस्मारक: आगामी बिलों और क्रेडिट कार्ड देय तिथियों के लिए ऑन-डिवाइस अलर्ट।
- होम स्क्रीन शॉर्टकट: खर्च, आय या ट्रांसफर तुरंत दर्ज करने के लिए 1-टैप शॉर्टकट।
- रिफंड और रिटर्न: आय को गलत तरीके से बढ़ाए बिना श्रेणी खर्च को सही ढंग से संतुलित करें।

• क्षेत्रीय और विज़ुअल अनुकूलन
- वैश्विक स्थानीयकरण: Android 13+ प्रति-ऐप भाषा समर्थन के साथ 13 भाषाओं में उपलब्ध।
- सार्वभौमिक मुद्राएं: ऑफ़लाइन विनिमय दरों के साथ 170+ मुद्राओं का समर्थन।
- 4 आइकन पैक शैलियाँ: Material Classic, Phosphor, Lucide और इमोजी।
- थीमिंग: 12 एक्सेंट रंगों के साथ 4 थीम मोड (सिस्टम, लाइट, डार्क, AMOLED ब्लैक)।
- संख्या प्रारूप: भारतीय (12,34,567.89) और अंतर्राष्ट्रीय (1,234,567.89) प्रारूपों के बीच स्विच करें।

• पूर्ण डेटा स्वामित्व और सुरक्षा
- शून्य इंटरनेट अनुमति: ऐप तकनीकी रूप से इंटरनेट से नहीं जुड़ सकता।
- बायोमेट्रिक लॉक: फिंगरप्रिंट, फेस अनलॉक या डिवाइस पिन से रिकॉर्ड सुरक्षित करें।
- ऑफ़लाइन पीडीएफ स्टेटमेंट: डिवाइस पर ही विस्तृत खाता विवरण और टैक्स रिपोर्ट तैयार करें।
- पोर्टेबल बैकअप: स्वचालित रोलिंग बैकअप, पोर्टेबल ज़िप फ़ाइलें और स्प्रेडशीट CSV निर्यात।"""
}

# 6. Indonesian (id)
translations['id'] = {
    'lang_name': 'Indonesian (Bahasa Indonesia)',
    'app_name': 'Folio: Buku Kas & Pengeluaran',
    'short_description': 'Buku kas & pelacak pengeluaran offline. Tanpa iklan, tanpa biaya, 100% privat.',
    'full_description': """Folio adalah aplikasi pengelola keuangan pribadi, pelacak pengeluaran, dan buku kas berpasangan (double-entry) 100% offline untuk kejelasan finansial dan privasi total.

Berbeda dari aplikasi yang membaca SMS, menjual data, atau meminta biaya langganan, Folio menyimpan seluruh data hanya di database SQLite lokal ponsel Anda: tanpa iklan, tanpa server cloud, tanpa izin internet, dan tanpa biaya tersembunyi.

🛡️ 100% GRATIS, PRIVAT & TERBUKA PENUH
• Tanpa Iklan: Bebas pop-up, banner, atau iklan sponsor.
• Tanpa Langganan: Semua fitur utama terbuka penuh selamanya.
• 100% Offline: Nol izin internet. Data Anda tidak pernah keluar dari ponsel.
• Tanpa Akun: Tanpa email atau registrasi. Langsung pakai seketika.

FITUR UTAMA:

• Buku Kas 5 Tampilan
Pantau arus kas dari lima sudut pandang khusus:
- Harian: Riwayat transaksi dengan pemasukan, pengeluaran, dan saldo bersih harian.
- Kalender: Kalender bulanan dengan titik visual arus kas. Ketuk hari untuk melihat atau mencatat.
- Mingguan: Pengelompokan mingguan untuk memantau ritme belanja bulanan.
- Bulanan: Ringkasan 12 bulan dengan rincian mingguan yang dapat diperluas.
- Ringkasan: Rasio tabungan %, saldo berjalan, kategori teratas, dan ekspor CSV 1 ketukan.

• Kokpit Kartu Kredit & Kekayaan Bersih
Kelola Kas, Rekening Bank, Tabungan, Investasi, Pinjaman, dan Kartu Kredit di satu tempat:
- Cerdas Kartu Kredit: Memisahkan tagihan jatuh tempo dari transaksi berjalan secara otomatis.
- Penasihat Gesek: Rekomendasi kartu terbaik untuk memaksimalkan masa bebas bunga.
- Pengukur Utilisasi 30%: Menjaga pemakaian limit kredit Anda di batas aman.
- Pelunasan 1 Ketukan: Bayar tagihan dari bank tanpa pencatatan ganda.
- Rekonsiliasi Bank: Audit saldo berjalan dan cocokkan transaksi dengan mudah.
- Kartu Debit Terhubung: Memotong otomatis dari rekening induk dengan mutasi terpisah.
- Portofolio Investasi: Pantau modal pokok vs. nilai pasar dengan analitik keuntungan.

• Anggaran & Brankas Tabungan
- Aman Dibelanjakan Hari Ini: Uang harian aman setelah menyisihkan tagihan 14 hari ke depan.
- Jarum Laju Belanja: Indikator visual apakah belanja lebih cepat atau lambat dari kalender.
- Brankas Tabungan: Target tabungan double-entry yang terhubung langsung ke rekening aset riil.
- Delta Komparatif: Tren pengeluaran dibanding minggu, bulan, atau tahun lalu.

• Pencatatan Cepat & Pengingat
- Papan angka cepat dengan kalkulator langsung (+, −, ×, ÷).
- Transaksi Terpisah (Split): Bagi satu nota belanja ke beberapa kategori dengan presisi.
- Transaksi Berulang: Jadwal aturan berulang (Harian, Mingguan, Bulanan, Akhir Bulan).
- Pengingat Tagihan: Notifikasi lokal untuk tagihan rutin dan jatuh tempo kartu kredit.
- Pintasan Beranda: Akses cepat mencatat Pengeluaran, Pemasukan, atau Transfer.
- Retur & Pengembalian: Sesuaikan pengeluaran tanpa menaikkan pemasukan fiktif.

• Kustomisasi Visual & Regional
- Lokalisasi Global: Diterjemahkan ke 13 bahasa dengan dukungan per-aplikasi Android 13+.
- Ragam Mata Uang: Mendukung 170+ mata uang dengan kurs manual offline.
- 4 Pilihan Ikon: Material Classic, Phosphor, Lucide, dan Emoji.
- Tema: 4 mode (Sistem, Terang, Gelap, AMOLED Hitam) dengan 12 aksen warna.
- Format Angka: Pilihan format penomoran internasional dan lokal.

• Privasi & Kepemilikan Data Penuh
- Nol Izin Internet: Aplikasi terisolasi penuh dari jaringan internet.
- Kunci Biometrik: Amankan data dengan Sidik Jari, Wajah, atau PIN perangkat.
- Laporan PDF Offline: Buat rekening koran dan laporan keuangan langsung di ponsel.
- Cadangan Portabel: Cadangan sesi otomatis, arsip ZIP, dan ekspor CSV."""
}

# 7. Italian (it-IT)
translations['it-IT'] = {
    'lang_name': 'Italian (Italiano)',
    'app_name': 'Folio: Spese e Bilancio',
    'short_description': 'Gestione spese e budget offline. Zero pubblicità, nessun abbonamento, privato.',
    'full_description': """Folio è un'app di finanza personale, tracciamento spese e libro mastro a partita doppia 100% offline, creata per offrirti assoluta chiarezza finanziaria e totale privacy.

A differenza delle app che leggono i tuoi SMS, vendono i tuoi dati o impongono abbonamenti mensili, Folio conserva ogni dato solo nel database SQLite locale del tuo smartphone: zero pubblicità, nessun server cloud, zero autorizzazioni internet e nessun costo nascosto.

🛡️ 100% GRATUITO, PRIVATO E SBLOCCATO
• Zero Pubblicità: Nessun pop-up, banner o contenuto promozionale.
• Nessun Abbonamento: Tutte le funzionalità principali sono sempre accessibili.
• 100% Offline: Nessuna autorizzazione internet. I dati non lasciano mai il dispositivo.
• Nessun Account Richiesto: Nessuna registrazione né email. Inizia subito.

FUNZIONALITÀ PRINCIPALI:

• Libro Mastro in 5 Viste
Esamina il tuo flusso di cassa da cinque prospettive dedicate:
- Giornaliera: Registro cronologico con entrate, uscite e saldo netto giornaliero.
- Calendario: Griglia mensile con indicatori grafici. Tocca un giorno per visualizzare o registrare.
- Settimanale: Raggruppamenti settimanali per monitorare il ritmo di spesa nel mese.
- Mensile: Panoramica annuale a 12 mesi con dettagli settimanali espandibili.
- Riepilogo: Tasso di risparmio %, saldo riportato, categorie ed esportazione CSV in 1 tocco.

• Carte di Credito e Patrimonio Netto
Gestisci Contanti, Conti Correnti, Risparmi, Investimenti, Prestiti e Carte di Credito:
- Gestione Intelligente Carte: Separa estratto conto dovuto dalle spese correnti.
- Assistente Pagamenti: Suggerisce la carta ideale per massimizzare il periodo senza interessi.
- Monitor Utilizzo 30%: Indicatori proattivi per mantenere l'uso del credito entro i limiti.
- Saldo Carta in 1 Tocco: Salda le carte dal conto bancario senza duplicare le spese.
- Riconciliazione Bancaria: Verifica i saldi progressivi e riconcilia le voci con facilità.
- Carte di Debito Collegate: Addebita sul conto principale isolando le spese con carta.
- Portafogli di Investimento: Monitora il capitale versato vs. valore di mercato con rendimenti.

• Budget Previsionali e Casseforti
- Disponibile Oggi (Safe-to-Spend): Budget spendibile al netto delle spese fisse a 14 giorni.
- Indicatori di Andamento: Mostrano visivamente se la spesa segue il calendario.
- Casseforti di Risparmio: Obiettivi di risparmio a partita doppia collegati ai tuoi conti reali.
- Confronti Temporali: Trend di spesa rispetto alla settimana, mese o anno scorso.

• Inserimento Rapido e Promemoria
- Tastierino numerico rapido con calcolatrice integrata (+, −, ×, ÷).
- Spese Frazionate: Suddividi una singola spesa su più categorie fino all'ultimo centesimo.
- Movimenti Ricorrenti: Regole flessibili (Giornaliere, Settimanali, Mensili, Fine Mese).
- Promemoria Scadenze: Notifiche locali per bollette e chiusura estratti conto.
- Scorciatoie Schermata Home: Accesso rapido per registrare Spese, Entrate o Giroconti.
- Rimborsi e Resi: Compensa le spese di categoria senza incrementare falsamente le entrate.

• Personalizzazione Visiva e Regionale
- Localizzazione Globale: Tradotto in 13 lingue con supporto lingua per app Android 13+.
- Valute Mondiali: Supporta oltre 170 valute con tassi di cambio personalizzati offline.
- 4 Pacchetti di Icone: Material Classic, Phosphor, Lucide ed Emoji espressive.
- Temi Grafici: 4 modalità (Sistema, Chiaro, Scuro, AMOLED Nero) con 12 accenti di colore.
- Formattazione Numerica: Passa facilmente dal formato italiano a quello internazionale.

• Privacy e Sicurezza Assolute
- Zero Permessi Internet: L'app è tecnicamente isolata da qualunque connessione di rete.
- Blocco Biometrico: Proteggi i registri con impronta digitale, volto o PIN.
- Estratti Conto PDF Offline: Genera report fiscali e stati patrimoniali sul dispositivo.
- Backup Portatili: Salvataggi automatici continuativi, archivi ZIP ed export CSV."""
}

# 8. Japanese (ja-JP)
translations['ja-JP'] = {
    'lang_name': 'Japanese (日本語)',
    'app_name': 'Folio: 家計簿とお小遣い帳',
    'short_description': '完全オフラインの家計簿＆予算管理。広告ゼロ、課金なし、安心のプライバシー。',
    'full_description': """Folio（フォリオ）は、完全オフラインで動作する個人資産管理・家計簿・複式簿記アプリです。広告や不要な機能を排し、確かな資産の透明性と完全なプライバシーを提供します。

SMSを読み取ったり、個人データを外部送信したり、高額な月額課金を要求する他のアプリとは異なり、Folioはすべての財務記録をお使いのスマートフォンのローカルSQLiteデータベース内にのみ保存します。広告なし、クラウド通信なし、インターネット通信権限なし、課金要素なしの安心設計です。

🛡️ 100%無料・完全プライベート・全機能解放
• 広告ゼロ: 画面を邪魔するポップアップやバナー広告は一切ありません。
• サブスクリプション・課金なし: すべての主要機能を完全無料で利用できます。
• 完全オフライン: インターネット通信権限自体を持たないため、データが端末外に出ることはありません。
• アカウント登録不要: メールアドレスやログインは不要。インストール後すぐに使えます。

主な機能:

• 5つの表示スタイルを持つ元帳
目的に応じて5つの画面からキャッシュフローを把握できます:
- 日別: 日々の収入・支出・差引残高を時系列レシート形式で確認。
- カレンダー: キャッシュフローが色分けドットでわかる月間カレンダー。タップして詳細を確認・即時記録。
- 週別: 締め日に合わせた週単位の支出ペース管理。
- 月別: 週ごとの内訳を展開できる12ヶ月の年間収支サマリー。
- サマリー: 貯蓄率%、繰越残高、上位支出カテゴリ、1タップでのCSVエクスポート。

• クレジットカード管理＆純資産コックピット
現金、銀行口座、貯金、投資信託、ローン、クレジットカードを一元管理:
- クレジットカード管理: 確定した請求額と未確定の利用分を自動で分離集計。
- スマートカードアドバイザー: 無利息の支払い猶予期間を最大化できる最適なカードを提案。
- 限度額30%目安メーター: 信用枠の使いすぎを防ぐ視覚的インジケーター。
- 1タップでの引き落とし精算: 支出を二重計上することなく、銀行口座からの引き落としを記録。
- 銀行通帳＆残高照合: 帳簿上の残高と実際の口座残高を1タップで照合。
- 紐付けデビットカード: 親口座から即時引き落としつつ、カード単体の利用明細を追跡。
- 投資ポートフォリオ: 投資元本と現在の評価額を記録し、評価損益を視覚化。

• 先読み予算管理＆貯蓄目標（ウェルス・ヴォールト）
- 今日使えるお金（Safe-to-Spend）: 今後14日間の請求や支払いを確保した上で、自由に使える日別予算を算出。
- ペーシング針: 月の経過日数に対して支出ペースが早いか遅いかを直感的に表示。
- 貯蓄ヴォールト: 実際の資産口座と直接連動した複式簿記ベースの目標貯蓄トラッカー。
- 前期比較デルタ: 先週・先月・前年同月との支出推移を比較するマイクロバッジ。

• 素早い入力機能＆支払期日リマインダー
- 電卓機能（+, −, ×, ÷）を内蔵した高速テンキー入力。
- 支出の内訳分割（スプリット）: 1枚のレシートを複数のカテゴリに1円単位で正確に配分。
- 定期取引・サブスク管理: 毎日、毎週、毎月、月末などの繰り返しルールを設定。
- 支払期日リマインダー: 固定費やカード請求日を端末内のローカル通知でお知らせ。
- ホーム画面ショートカット: アプリアイコン長押しから1タップで支出・収入・振替を入力。
- 返品・払い戻し対応: 収入を不当に水増しすることなく、特定カテゴリの支出を相殺。

• 柔軟なデザイン＆カスタマイズ
- 多言語対応: Android 13以降の「アプリごとの言語設定」に対応した13言語サポート。
- 世界の通貨に対応: オフラインの手動レート設定が可能な170以上の通貨に対応。
- 4つのアイコンパック: Material Classic、Phosphor、Lucide、親しみやすい絵文字。
- テーマ設定: 4つの表示モード（システム、ライト、ダーク、AMOLED漆黒）と12色のアクセントカラー。
- 数値フォーマット: 桁区切りや通貨記号の表示形式を自在に切り替え。

• 妥協のないデータ所有権とセキュリティ
- インターネット権限なし: アプリが外部ネットワークと通信することは一切不可能です。
- 生体認証ロック: 指紋認証、顔認証、端末PINコードで大切な資産データを保護。
- オフラインPDF明細作成: 口座取引明細や年間レポートを端末内で美しくPDF出力。
- 自由なバックアップ: 自動セッションバックアップ、ポータブルZIPアーカイブ、CSV形式でのエクスポート。"""
}

# 9. Korean (ko-KR)
translations['ko-KR'] = {
    'lang_name': 'Korean (한국어)',
    'app_name': 'Folio: 가계부 & 지출 관리',
    'short_description': '완전 오프라인 가계부 및 예산 관리. 광고 없음, 유료 결제 없음, 100% 개인정보 보호.',
    'full_description': """Folio(폴리오)는 완벽한 금융 명확성과 철저한 개인정보 보호를 위해 설계된 100% 오프라인 개인 자산 관리, 지출 추적 및 복식부기 가계부 앱입니다.

사용자의 SMS를 무단으로 읽거나, 금융 데이터를 외부에 판매하거나, 매달 구독료를 청구하는 여타 앱들과 달리, Folio는 사용자의 모든 기록을 휴대폰 로컬 SQLite 데이터베이스에만 안전하게 보관합니다. 광고, 클라우드 서버, 인터넷 통신 권한, 결제 유도 기능이 전혀 없습니다.

🛡️ 100% 무료, 완벽한 보안 & 모든 기능 잠금 해제
• 광고 제로: 화면을 가리는 팝업, 배너, 스폰서 광고가 일절 없습니다.
• 구독료 및 결제 없음: 모든 핵심 기능이 영구적으로 완전 무료 제공됩니다.
• 100% 오프라인: 인터넷 접근 권한이 없습니다. 금융 데이터가 기기 밖으로 유출되지 않습니다.
• 계정 가입 불필요: 이메일 등록이나 로그인이 필요 없습니다. 설치 즉시 사용 가능합니다.

핵심 기능:

• 5가지 뷰를 지원하는 메인 장부
5가지 전용 화면을 통해 현금 흐름을 다각도로 분석하세요:
- 일별: 일일 수입, 지출 및 순 잔액을 한눈에 보는 시간순 영수증 피드.
- 캘린더: 시각적 점으로 현금 흐름을 표시하는 월간 달력. 날짜를 눌러 즉시 확인 및 입력.
- 주별: 결제 주기에 맞춘 주간 소비 페이스 모니터링.
- 월별: 주간 상세 분석을 확장할 수 있는 12개월 연간 수입/지출 개요.
- 요약: 저축률 %, 이월 잔액, 상위 지출 카테고리 및 원터치 CSV 내보내기.

• 신용카드 콕핏 & 순자산 관리
현금, 은행 계좌, 예적금, 투자, 대출, 신용카드를 한곳에서 스마트하게 관리:
- 신용카드 인텔리전스: 결제 청구 금액과 미청구 사용액을 자동으로 명확히 분리.
- 스마트 결제 어드바이저: 무이자 유예 기간을 최대화할 수 있는 최적의 결제 카드를 추천.
- 30% 한도 소진율 게이지: 신용 한도 과다 사용을 방지하는 실시간 게이지 알림.
- 원터치 카드 대금 결제: 지출 중복 계산 없이 은행 계좌에서 카드 대금을 정산.
- 은행 통장 및 잔액 대조: 누적 잔액을 검토하고 터치 한 번으로 실제 통장과 대조.
- 연결된 체크카드: 결제 시 주 계좌에서 자동 차감하면서도 카드 단독 지출 분리 관리.
- 투자 포트폴리오: 투자 원금 대비 현재 평가 금액을 기록하고 손익률 추적.

• 스마트 예산 & 저축 금고 (Wealth Vaults)
- 오늘 사용 가능 금액 (Safe-to-Spend): 다가오는 14일간의 고정 지출을 확보한 후 오늘 쓸 수 있는 여유 자금 계산.
- 페이싱 바늘: 현재 날짜 대비 소비 속도가 빠른지 느린지 직관적으로 보여주는 시각 지표.
- 저축 금고: 실제 금융 계좌와 연결된 복식부기 기반 저축 목표 관리.
- 비교 델타 뱃지: 지난주, 지난달, 작년 동기 대비 지출 추이를 한눈에 비교.

• 신속한 입력 & 결제일 알림
- 실시간 계산기(+, −, ×, ÷)가 내장된 빠른 숫자 키패드.
- 지출 분할: 영수증 하나를 여러 카테고리로 원 단위까지 정밀하게 분할.
- 정기 거래: 매일, 매주, 매월, 매월 말일 등 유연한 반복 규칙 설정.
- 만기일 알림: 청구서 및 신용카드 결제일을 로컬 알림으로 사전 안내.
- 홈 화면 바로가기: 앱 아이콘을 길게 눌러 지출, 수입, 이체를 즉시 기록.
- 환불 처리: 수입을 허위로 부풀리지 않고 해당 카테고리 지출을 정확히 상계.

• 맞춤형 디자인 & 글로벌 지원
- 다국어 지원: Android 13+ 앱별 언어 설정을 지원하는 13개 국어 번역 제공.
- 전 세계 통화: 오프라인 환율 설정이 가능한 170개 이상의 글로벌 통화 지원.
- 4가지 아이콘 팩: Material Classic, Phosphor, Lucide, 풍부한 이모지.
- 테마 모드: 12가지 강조 색상을 갖춘 4가지 테마(시스템, 라이트, 다크, AMOLED 블랙).
- 숫자 서식: 지역별 숫자 및 통화 표시 형식을 자유롭게 전환.

• 철저한 데이터 보안 & 소유권
- 인터넷 권한 완전 배제: 기술적으로 외부 네트워크와 통신할 수 없습니다.
- 생체 인증 잠금: 지문, 얼굴 인식, 기기 PIN으로 금융 정보를 안전하게 보호.
- 오프라인 PDF 보고서: 거래 내역서 및 연간 보고서를 기기 내에서 PDF로 생성.
- 이동식 백업: 자동 롤링 백업, ZIP 압축 파일 및 엑셀용 CSV 파일 내보내기 지원."""
}

# 10. Portuguese (pt-BR)
translations['pt-BR'] = {
    'lang_name': 'Portuguese (Português - Brasil)',
    'app_name': 'Folio: Gastos e Orçamento',
    'short_description': 'Controle de gastos e orçamento offline. Sem anúncios, sem assinaturas, privado.',
    'full_description': """O Folio é um gerenciador financeiro pessoal, controle de despesas e livro-razão de partidas dobradas 100% offline, feito para total clareza financeira e privacidade absoluta.

Diferente de aplicativos que leem seus SMS, vendem seus dados ou cobram assinaturas mensais, o Folio armazena seus registros estritamente no banco de dados local SQLite do seu celular: zero anúncios, sem nuvem, sem permissão de internet e sem custos ocultos.

🛡️ 100% GRATUITO, PRIVADO E DESBLOQUEADO
• Sem Anúncios: Livre de banners, pop-ups ou anúncios.
• Sem Assinaturas: Todos os recursos principais estão completamente liberados.
• 100% Offline: Sem permissão de internet. Seus dados nunca saem do aparelho.
• Sem Cadastro: Sem e-mail ou senhas. Comece a usar na hora.

PRINCIPAIS RECURSOS:

• Livro-Razão em 5 Visualizações
Acompanhe seu fluxo de caixa através de cinco perspectivas dedicadas:
- Diária: Linha do tempo cronológica com receitas, despesas e saldo líquido do dia.
- Calendário: Grade mensal com marcadores visuais. Toque em qualquer dia para ver ou lançar.
- Semanal: Blocos semanais para controlar seu ritmo de gastos no mês.
- Mensal: Visão anual de 12 meses com detalhamento semanal expansível.
- Resumo: Taxa de economia %, saldo acumulado, categorias e exportação CSV em 1 toque.

• Painel de Cartões de Crédito e Patrimônio
Gerencie Dinheiro, Contas Bancárias, Poupança, Investimentos, Empréstimos e Cartões:
- Cartão Inteligente: Separa a fatura fechada dos gastos em aberto automaticamente.
- Consultor de Compras: Sugere qual cartão usar para obter o maior prazo sem juros.
- Limite em 30%: Marcadores proativos ajudam a manter o uso do crédito em nível saudável.
- Quitação em 1 Toque: Pague a fatura debitando da conta sem duplicar despesas.
- Conciliação Bancária: Audite o saldo contínuo e faça a conciliação com facilidade.
- Cartões de Débito: Debita da conta corrente mantendo o extrato do cartão separado.
- Investimentos: Acompanhe valor aplicado vs. cotação de mercado com cálculo de retorno.

• Orçamentos e Cofres de Metas
- Disponível Hoje (Safe-to-Spend): Saldo diário livre protegendo contas dos próximos 14 dias.
- Marcador de Ritmo: Indicadores visuais que mostram se os gastos acompanham o mês.
- Cofres de Economia: Metas de economia com partidas dobradas vinculadas a contas reais.
- Indicadores Comparativos: Veja se gastos subiram ou desceram em relação ao mês anterior.

• Lançamento Ágil e Lembretes
- Teclado numérico ultra-rápido com calculadora integrada (+, −, ×, ÷).
- Transações Divididas (Split): Distribua uma compra entre várias categorias com centavos.
- Regras Recorrentes: Lançamentos programados (Diário, Semanal, Mensal, Fim de Mês).
- Lembretes de Vencimento: Notificações locais para contas fixas e faturas de cartão.
- Atalhos na Tela Inicial: Toque rápido no app para lançar Despesas, Receitas ou Transferências.
- Estornos e Devoluções: Abata despesas de uma categoria sem inflar a sua receita.

• Personalização Visual e Regional
- Localização Global: Traduzido para 13 idiomas com suporte por app do Android 13+.
- Moedas Globais: Suporta mais de 170 moedas com taxas de câmbio manuais offline.
- 4 Pacotes de Ícones: Material Classic, Phosphor, Lucide e Emojis expressivos.
- Temas: 4 modos (Sistema, Claro, Escuro, AMOLED Preto) com 12 paletas de cores.
- Formatação Numérica: Suporte ao formato brasileiro (R$ 1.234,56) e internacional.

• Segurança e Soberania dos Dados
- Sem Permissão de Internet: O app é tecnicamente impedido de se conectar à internet.
- Bloqueio Biométrico: Proteja dados com Impressão Digital, Reconhecimento Facial ou PIN.
- Extratos em PDF Offline: Gere demonstrativos e relatórios diretamente no telefone.
- Backups Portáteis: Backups contínuos automáticos, arquivos ZIP e exportações CSV."""
}

# 11. Tamil (ta-IN)
translations['ta-IN'] = {
    'lang_name': 'Tamil (தமிழ்)',
    'app_name': 'Folio: செலவு & வரவு புத்தகம்',
    'short_description': 'ஆஃப்லைன் செலவு கணக்கு மற்றும் வரவு புத்தகம். விளம்பரம் இல்லை, 100% தனிப்பட்டது.',
    'full_description': """ஃபோலியோ (Folio) என்பது 100% ஆஃப்லைனில் இயங்கும் தனிப்பட்ட நிதி மேலாளர், செலவு கண்காணிப்பு மற்றும் இரட்டைப் பதிவு கணக்குப் புத்தகமாகும் (Double-entry Ledger). இது முழுமையான நிதித் தெளிவு மற்றும் தனிநபர் ரகசியக் காப்பிற்காக உருவாக்கப்பட்டது.

உங்கள் எஸ்எம்எஸ் படிக்கும், தரவை விற்கும் அல்லது மாதாந்திர கட்டணம் வசூலிக்கும் செயலிகளைப் போலன்றி, Folio உங்கள் பதிவுகளை போனின் உள்ளக SQLite தரவுத்தளத்தில் மட்டுமே பாதுகாப்பாகச் சேமிக்கிறது—விளம்பரங்கள் இல்லை, கிளவுட் இல்லை, இணைய அனுமதி இல்லை மற்றும் கட்டணங்கள் இல்லை.

🛡️ 100% இலவசம், பாதுகாப்பானது மற்றும் முழுமையானது
• விளம்பரங்கள் இல்லை: தேவையற்ற பாப்-அப் அல்லது விளம்பரங்கள் எதுவும் இல்லை.
• சந்தா அல்லது கட்டணம் இல்லை: அனைத்து முக்கிய அம்சங்களும் எப்போதும் இலவசமாகத் திறக்கப்பட்டுள்ளன.
• 100% ஆஃப்லைன்: இணைய அனுமதி தேவையில்லை. உங்கள் தகவல்கள் போனை விட்டு வெளியேறாது.
• கணக்கு தேவையில்லை: மின்னஞ்சல் அல்லது பதிவு தேவையில்லை. உடனே தொடங்கலாம்.

முக்கிய அம்சங்கள்:

• 5 விதமான கணக்கு புத்தகம் (Ledger Views)
பணப்புழக்கத்தை ஐந்து பிரத்யேக கோணங்களில் எளிதாகக் கண்காணிக்கலாம்:
- தினசரி: தினசரி வருமானம், செலவு மற்றும் நிகர இருப்புடன் கூடிய ரசீது பட்டியல்.
- நாட்காட்டி: வண்ணப் புள்ளிகளுடன் கூடிய மாதாந்திர பணப்புழக்க காலண்டர்.
- வாராந்திரம்: உங்கள் நிதி மாதத்தின் வாராந்திர செலவு விகிதத்தை அறியும் அமைப்பு.
- மாதாந்திரம்: வாராந்திர விவரங்களுடன் கூடிய 12 மாத முழு ஆண்டு நிதி அறிக்கை.
- சுருக்கம்: சேமிப்பு விகிதம் %, தொடக்க இருப்பு, அதிக செலவு வகைகள் மற்றும் CSV ஏற்றுமதி.

• கிரெடிட் கார்டு & நிகர சொத்து மேலாண்மை
பணம், வங்கி கணக்குகள், சேமிப்பு, முதலீடு, கடன்கள் மற்றும் கார்டுகளை ஒரே இடத்தில் நிர்வகிக்கவும்:
- கார்டு நுண்ணறிவு: நிலுவைத் தொகை மற்றும் பில் செய்யப்படாத செலவுகளைத் தானாகவே பிரிக்கிறது.
- ஸ்மார்ட் வழிகாட்டி: வட்டி இல்லாத சலுகைக் காலத்தைப் பெற எந்த கார்டைப் பயன்படுத்தலாம் என பரிந்துரைக்கிறது.
- 30% வரம்பு அளவீடு: கார்டு வரம்பு பயன்பாட்டைப் பாதுகாப்பான அளவில் வைத்திருக்க உதவுகிறது.
- 1-தட்டல் பில் தீர்வு: செலவுகளை இருமுறை கூட்டாமல் வங்கியிலிருந்து கார்டு பில்களைச் செலுத்துங்கள்.
- வங்கி பாஸ்புக் சரிபார்ப்பு: தொடர் இருப்பைக் கண்காணித்து கணக்கைச் சரிபார்க்கவும்.
- இணைக்கப்பட்ட டெபிட் கார்டுகள்: கார்டு செலவுகளைத் தனிமைப்படுத்தி வங்கியிலிருந்து கழிக்கிறது.
- முதலீட்டுத் தொகுப்பு: அசல் தொகை மற்றும் சந்தை மதிப்பை லாபக் கணக்கீட்டுடன் கண்காணிக்கவும்.

• திட்டமிட்ட பட்ஜெட் & சேமிப்பு பெட்டகங்கள்
- இன்று செலவழிக்க பாதுகாப்பான தொகை: அடுத்த 14 நாள் பில்களை ஒதுக்கி வைத்துவிட்டு கணக்கிடுகிறது.
- வேகக் குறிகாட்டிகள்: செலவு வேகம் திட்டமிட்ட பட்ஜெட்டை விட முந்துகிறதா என பார்வைக்குக் காட்டும்.
- சேமிப்பு பெட்டகங்கள்: உங்கள் உண்மையான வங்கிக் கணக்குகளுடன் இணைக்கப்பட்ட இலக்கு சேமிப்பு.
- ஒப்பீட்டு பேட்ஜ்கள்: கடந்த வாரம், மாதம் அல்லது ஆண்டுடன் செலவுப் போக்கை ஒப்பிட்டுப் பார்க்கலாம்.

• விரைவான பதிவு & நினைவூட்டல்கள்
- நேரடி கணக்கீட்டுடன் கூடிய (+, −, ×, ÷) வேகமான எண் விசைப்பலகை.
- பல பிரிவுகளாகப் பிரித்தல்: ஒரு ரசீதை பைசா வரை துல்லியமாக பல பிரிவுகளாகப் பிரிக்கலாம்.
- தொடர் கட்டணங்கள்: தினசரி, வாராந்திர, மாதாந்திர அல்லது மாத இறுதி விதிகளைத் திட்டமிடலாம்.
- கட்டண நினைவூட்டல்கள்: வரவிருக்கும் பில்கள் மற்றும் கார்டு நிலுவைத் தேதிக்கான அறிவிப்புகள்.
- முகப்புத் திரை குறுக்குவழிகள்: செலவு, வருமானத்தை உடனடியாகப் பதிவு செய்ய 1-தட்டல் வழிகள்.
- பணத்தைத் திரும்பப்பெறுதல்: வருமானத்தை பொியாகக் காட்டாமல் செலவுகளைச் சரிக்கட்டலாம்.

• பயன்பாட்டுத் தனிப்பயனாக்கம்
- உலகளாவிய மொழிகள்: Android 13+ மொழி ஆதரவுடன் தமிழ் உட்பட 13 மொழிகளில் கிடைக்கிறது.
- உலகளாவிய நாணயங்கள்: ஆஃப்லைன் மாற்று விகிதங்களுடன் 170+ உலக நாணயங்களை ஆதரிக்கிறது.
- 4 ஐகான் தொகுப்புகள்: Material Classic, Phosphor, Lucide மற்றும் எமோஜிகள்.
- வண்ண அமைப்புகள்: 12 வண்ணங்களுடன் 4 தீம்கள் (System, Light, Dark, AMOLED Black).
- எண் வடிவங்கள்: இந்திய மற்றும் சர்வதேச எண் வடிவங்களுக்கு இடையே மாறலாம்.

• முழுமையான பாதுகாப்பு & தரவு உரிமை
- இணைய அனுமதி இல்லை: இந்தச் செயலி இணையத்தை அணுக முடியாது.
- பயோமெட்ரிக் பூட்டு: கைரேகை, முக அங்கீகாரம் அல்லது சாதன பின் மூலம் பாதுகாக்கலாம்.
- ஆஃப்லைன் PDF அறிக்கைகள்: விரிவான கணக்கு அறிக்கைகளை போனிலேயே PDF ஆக உருவாக்கலாம்.
- எளிதான காப்புப்பிரதி: தானியங்கி பேக்கப், ZIP கோப்புகள் மற்றும் CSV எக்செல் ஏற்றுமதி."""
}

# 12. Chinese Simplified (zh-CN)
translations['zh-CN'] = {
    'lang_name': 'Chinese Simplified (简体中文)',
    'app_name': 'Folio: 记账与个人财务管家',
    'short_description': '完全离线个人记账与预算管家。零广告，无付费门槛，100%保护隐私。',
    'full_description': """Folio 是一款 100% 完全离线运行的个人财务管理、记账与复式簿记（Double-entry Ledger）工具，专为极致的财务清晰度与绝对的数据隐私而设计。

与那些读取您短信、贩卖财务隐私或收取高额月费的联网应用截然不同，Folio 将所有财务数据严格保存在手机本地的 SQLite 数据库中——零广告、无云端服务器、无网络访问权限、无付费陷阱。

🛡️ 100% 免费、完全私密、功能全解锁
• 零广告骚扰: 绝无弹窗、横幅或赞助商推广内容。
• 绝无内购与订阅: 所有核心功能完全永久解锁。
• 100% 离线安全: 应用未申请任何网络权限，数据绝不可能离开您的设备。
• 无需注册账号: 免邮箱、免登录，打开即用。

核心功能概览：

• 5 维视角核心账本
通过五个精心设计的视角全方位掌控现金流：
- 日常账本 (Daily): 按时间顺序排列的流水清单，实时汇总每日收入、支出与净结余。
- 日历视图 (Calendar): 带彩色收支圆点的月度日历，点击任意一天即可查看详情或快速记账。
- 周度视角 (Weekly): 按账单月聚类各周支出速率，防止月末超支。
- 年度月报 (Monthly): 12 个月的年度收支总览，支持逐周展开下钻明细。
- 财务概览 (Summary): 实时显示储蓄率 %、结转余额、热门支出分类，并支持一键导出 CSV。

• 信用卡控制台与净资产管理
在一处统一管理现金、银行账户、储蓄、理财投资、贷款及信用卡：
- 信用卡智能分账: 自动将已出账单应还金额与未出账单消费彻底分离。
- 智能刷卡助手: 结合账单日与还款日，智能推荐能享受最长免息期的卡片。
- 30% 额度预警仪表盘: 直观仪表盘实时监控授信额度占用率，保持良好信用健康度。
- 一键账单还款: 直接从银行卡扣款还款，杜绝重复记账导致支出虚高。
- 银行流水与快速对账: 随时核对流水连续性，支持一键快速对账。
- 绑定借记卡: 自动扣减关联储蓄卡余额，同时保留独立的卡片消费记录。
- 投资理财组合: 记录投入本金与最新估值，清晰呈现未实现收益与盈亏比例。

• 前瞻性预算与财富储蓄库
- 今日安全可消费额 (Safe-to-Spend): 自动扣除未来 14 天待缴账单与固定负债，精准算出今日自由支配金额。
- 支出节奏指示针: 动态指针直观对比当前开销速度是否超前于日历天数。
- 财富储蓄库: 基于复式记账法构建的愿望储蓄目标，直连真实资产账户。
- 周期对比徽章: 快速对比上周、上月或去年的消费趋势变化。

• 极速录入与到期提醒
- 内置实时算术计算器 (+, −, ×, ÷) 的高速数字键盘。
- 拆分记账 (Split): 单张小票支出可按分厘精确拆分到多个支出类目。
- 周期性交易: 支持灵活的定期记账规则（每天、每周、每月、月末等）。
- 账单到期提醒: 针对固定开销与信用卡还款日提供纯本地通知提醒。
- 桌面快捷方式: 长按桌面图标一键直达记支出、记收入或转账。
- 退款冲销记账: 准确抵扣对应类别的消费总额，绝不虚增当月收入。

• 视觉外观与本地化定制
- 全球多语言支持: 完美适配 13 种主流语言，支持 Android 13+ 单应用语言设置。
- 覆盖全球货币: 支持 170+ 种货币，并提供离线自定义汇率换算。
- 4 款精美图标包: Material Classic、Phosphor、Lucide 以及生动形象的 Emoji。
- 多彩主题模式: 4 种显示模式（跟随系统、浅色、深色、AMOLED 纯黑），搭配 12 种强调色。
- 数值格式切换: 支持国际标准千分位及各区域专用数字格式。

• 坚不可摧的隐私与数据所有权
- 零网络权限: 代码层面杜绝联网能力，物理隔绝任何数据泄露风险。
- 生物识别锁定: 支持指纹解锁、面部识别或设备 PIN 码保护账本。
- 本地高清 PDF 账单: 在手机端直接渲染生成精美账户对账单与年度财务报告。
- 自由便携备份: 自动滚动备份、便携式 ZIP 完整归档，以及电子表格 CSV 导出。"""
}

# 13. Chinese Traditional (zh-TW)
translations['zh-TW'] = {
    'lang_name': 'Chinese Traditional (繁體中文)',
    'app_name': 'Folio: 記帳與個人財務管家',
    'short_description': '完全離線個人記帳與預算管家。零廣告，無付費門檻，100%保護隱私。',
    'full_description': """Folio 是一款 100% 完全離線運作的個人財務管理、記帳與複式簿記（Double-entry Ledger）工具，專為極致的財務清晰度與絕對的資料隱私而設計。

與那些讀取您簡訊、販售個人隱私或收取高額月費的聯網應用程式截然不同，Folio 將所有財務紀錄嚴格保存在手機本機的 SQLite 資料庫中——零廣告、無雲端伺服器、無網路連線權限、無付費門檻。

🛡️ 100% 免費、完全隱私、功能全解鎖
• 零廣告干擾: 絕無彈出視窗、橫幅或贊助商廣告。
• 絕無內購與訂閱: 所有核心功能完全永久解鎖。
• 100% 離線安全: 應用程式未申請任何網路權限，資料絕不離開您的裝置。
• 無需註冊帳號: 免電子郵件、免登入，安裝即可立即使用。

核心功能概覽：

• 5 種視角核心帳本
透過五個精心設計的視角全方位掌控現金流：
- 日常帳本 (Daily): 按時間順序排列的收據清單，即時彙整每日收入、支出與淨結餘。
- 行事曆檢視 (Calendar): 帶有彩色收支圓點的月度月曆，點擊任意一天即可檢視明細或快速記帳。
- 每週視角 (Weekly): 依據帳單月份彙整各週支出速率，防止在月底超支。
- 年度月報 (Monthly): 12 個月的年度收支總覽，支援逐週展開檢視明細。
- 財務總覽 (Summary): 即時顯示儲蓄率 %、結轉餘額、熱門支出類別，並支援一鍵匯出 CSV。

• 信用卡控制台與淨資產管理
在一處統一管理現金、銀行帳戶、儲蓄、理財投資、貸款及信用卡：
- 信用卡智慧分帳: 自動將已出帳單應繳金額與未出帳單消費徹底分離。
- 智慧刷卡顧問: 結合結帳日與繳款日，智慧推薦能享受最長免息期的信用卡。
- 30% 額度預警儀表板: 直覺儀表板即時監控信用額度佔用率，維持良好信用評分。
- 一鍵帳單繳款: 直接從銀行帳戶扣款繳費，杜絕重複記帳導致支出虛增。
- 銀行流水與快速對帳: 隨時核對帳戶餘額，支援一鍵快速對帳。
- 綁定簽帳金融卡: 自動扣除關聯儲蓄帳戶餘額，同時保留獨立的卡片消費紀錄。
- 投資理財組合: 記錄投入本金與最新估值，清晰呈現未實現損益與報酬率。

• 前瞻性預算與財富儲蓄庫
- 今日安全可花費額 (Safe-to-Spend): 自動扣除未來 14 天待繳帳單與固定負債，精準算出今日自由支配金額。
- 支出步調指示針: 動態指標直觀對比當前花費速度是否超前於日曆天數。
- 財富儲蓄庫: 基於複式記帳法構建的願望儲蓄目標，直連真實資產帳戶。
- 週期對比標籤: 快速對比上週、上月或去年的消費趨勢變化。

• 極速輸入與到期提醒
- 內建即時算術計算機 (+, −, ×, ÷) 的高速數字鍵盤。
- 拆分記帳 (Split): 單筆發票支出可精確拆分到多個支出類別。
- 週期性交易: 支援靈活的定期記帳規則（每天、每週、每月、月底等）。
- 帳單到期提醒: 針對固定開銷與信用卡繳費日提供純本機通知提醒。
- 桌面捷徑: 長按桌面圖示一鍵直達記支出、記收入或轉帳。
- 退款沖銷記帳: 準確抵扣對應類別的消費總額，絕不虛增當月收入。

• 視覺外觀與在地化自訂
- 全球多語言支援: 完美支援 13 種主流語言，支援 Android 13+ 單一應用程式語言設定。
- 涵蓋全球貨幣: 支援 170+ 種貨幣，並提供離線自訂匯率換算。
- 4 款精美圖示包: Material Classic、Phosphor、Lucide 以及生動活潑的 Emoji。
- 多彩主題模式: 4 種顯示模式（跟隨系統、淺色、深色、AMOLED 純黑），搭配 12 種強調色。
- 數值格式切換: 支援國際標準千分位及各區域專用數字格式。

• 堅不可摧的隱私與資料所有權
- 零網路權限: 程式碼層面完全杜絕聯網能力，物理隔絕任何資料外洩風險。
- 生物辨識鎖定: 支援指紋解鎖、臉部辨識或裝置 PIN 碼保護帳本。
- 本地高畫質 PDF 帳單: 在手機端直接渲染生成精美帳戶對帳單與年度財務報告。
- 自由便攜備份: 自動輪替備份、便攜式 ZIP 完整封存，以及試算表 CSV 匯出。"""
}

# Verification & Export
output_dir = r"c:\Users\sivas\AndroidStudioProjects\Folio\play_store_metadata\translations"
os.makedirs(output_dir, exist_ok=True)

errors = []
master_csv_rows = [["Language Code", "Language Name", "App Title", "Short Description", "Full Description"]]

for code, data in translations.items():
    title = data['app_name']
    short_desc = data['short_description']
    full_desc = data['full_description']
    
    t_len = len(title)
    s_len = len(short_desc)
    f_len = len(full_desc)
    
    if t_len > 30:
        errors.append(f"[{code}] Title length {t_len} > 30: '{title}'")
    if s_len > 80:
        errors.append(f"[{code}] Short description length {s_len} > 80: '{short_desc}'")
    if f_len > 4000:
        errors.append(f"[{code}] Full description length {f_len} > 4000")
        
    master_csv_rows.append([code, data['lang_name'], title, short_desc, full_desc])
    
    # Write individual txt file
    file_path = os.path.join(output_dir, f"listing_{code}.txt")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(f"=== APP TITLE (Max 30) ===\n{title}\n\n")
        f.write(f"=== SHORT DESCRIPTION (Max 80) ===\n{short_desc}\n\n")
        f.write(f"=== FULL DESCRIPTION (Max 4000) ===\n{full_desc}\n")

if errors:
    print("VALIDATION ERRORS:")
    for e in errors:
        print(" -", e)
else:
    print("ALL 13 LANGUAGES VALIDATED SUCCESSFULLY!")
    for code, data in translations.items():
        print(f"[{code:7}] Title: {len(data['app_name']):2}/30 | Short: {len(data['short_description']):2}/80 | Full: {len(data['full_description']):4}/4000")

# Write Master CSV
csv_path = r"c:\Users\sivas\AndroidStudioProjects\Folio\play_store_metadata\all_languages_listing.csv"
with open(csv_path, "w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    for row in master_csv_rows:
        writer.writerow(row)

# Write Master JSON
json_path = r"c:\Users\sivas\AndroidStudioProjects\Folio\play_store_metadata\all_languages_listing.json"
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(translations, f, ensure_ascii=False, indent=2)

print(f"\nSaved CSV: {csv_path}")
print(f"Saved JSON: {json_path}")
print(f"Saved 13 Individual TXT files in: {output_dir}")
