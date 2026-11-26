/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import com.folioledger.app.domain.model.CategoryType
import java.util.Locale

object CategorySuggestionHelper {
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

    fun suggestIconAndColorForCategoryName(
        name: String,
        type: CategoryType = CategoryType.EXPENSE,
    ): Pair<String, String>? {
        val normalized = name.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return null

        val tokens = normalized.split(NON_ALNUM_REGEX)
            .filter { it.isNotEmpty() }
            .toSet()
        val spaced = " " + normalized.replace(NON_ALNUM_REGEX, " ").trim() + " "

        fun matchesAny(vararg keywords: String): Boolean =
            keywords.any { kw ->
                val cleanKw = kw.trim()
                if (' ' in cleanKw) {
                    " $cleanKw " in spaced
                } else {
                    cleanKw in tokens || (cleanKw.length >= 4 && tokens.any { token ->
                        token == "${cleanKw}s" || token == "${cleanKw}es"
                    })
                }
            }

        return when {
            // Specific multi-word phrases & previously shadowed terms before broad tokens
            matchesAny("travel insurance") ->
                "security" to "#0D9488"
            matchesAny("credit card bill") ->
                "receipt_long" to "#E11D48"
            matchesAny("board game") ->
                "toys" to "#F97316"
            matchesAny("daycare") ->
                "child_care" to "#EC4899"
            matchesAny("barber") ->
                "content_cut" to "#EC4899"
            matchesAny("payroll") ->
                "payments" to "#059669"
            matchesAny("passport") ->
                "luggage" to "#4F46E5"

            // Food & Beverages
            matchesAny("coffee", "tea", "chai", "cafe", "starbucks", "filter coffee", "beverage") ->
                "local_cafe" to "#D97706"
            matchesAny("bakery", "cake", "pastry", "sweet", "mithai") ->
                "bakery_dining" to "#EC4899"
            matchesAny("icecream", "ice cream", "gelato", "dessert") ->
                "icecream" to "#EC4899"
            matchesAny("bar", "pub", "alcohol", "beer", "wine", "liquor", "drinks") ->
                "local_bar" to "#7C3AED"
            matchesAny("fastfood", "fast food", "burger", "pizza", "domino", "dominos", "kfc", "mcdonald", "mcdonalds", "snack", "roll", "wrap", "faasos") ->
                "fastfood" to "#F97316"
            matchesAny("swiggy", "zomato", "food", "dining", "restaurant", "lunch", "dinner", "breakfast", "tiffin", "idli", "dosa", "bhavan", "biryani", "meal") ->
                "restaurant" to "#F97316"

            // Groceries & Shopping
            matchesAny("grocery", "groceries", "kirana", "mart", "instamart", "blinkit", "zepto", "bigbasket", "supermarket", "provision", "vegetable", "fruit", "milk", "dairy") ->
                "shopping_cart" to "#10B981"
            matchesAny("mall", "department store", "hypermarket") ->
                "local_mall" to "#EC4899"
            matchesAny("shopping", "amazon", "flipkart", "meesho", "online order", "store") ->
                "shopping_bag" to "#EC4899"

            // Transport & Travel
            matchesAny("fuel", "petrol", "diesel", "shell", "hpcl", "bpcl", "iocl", "cng", "gas station") ->
                "local_gas_station" to "#E11D48"
            matchesAny("ev", "evcharging", "charging", "ather", "electric vehicle") ->
                "ev_station" to "#0D9488"
            matchesAny("uber", "ola", "rapido", "cab", "cabs", "taxi", "auto", "rickshaw") ->
                "local_taxi" to "#D97706"
            matchesAny("bike", "scooter", "two wheeler", "motorcycle", "raider", "activa", "helmet") ->
                "two_wheeler" to "#0284C7"
            matchesAny("car", "cars", "vehicle", "parking", "fastag", "toll", "car wash") ->
                "directions_car" to "#2563EB"
            matchesAny("metro", "subway", "cmrl", "dmrc", "bmrc") ->
                "subway" to "#0D9488"
            matchesAny("train", "irctc", "railway", "rail", "loco") ->
                "train" to "#2563EB"
            matchesAny("bus", "buses", "redbus", "mtc", "ksrtc", "transit", "commute", "pass") ->
                "directions_bus" to "#0284C7"
            matchesAny("flight", "airline", "indigo", "air india", "vistara", "spicejet", "airfare", "airport") ->
                "flight" to "#4F46E5"
            matchesAny("hotel", "resort", "airbnb", "oyo", "stay", "lodging") ->
                "hotel" to "#0D9488"
            matchesAny("beach", "holiday", "vacation", "tour", "sightseeing") ->
                "beach_access" to "#0284C7"
            matchesAny("travel", "trip", "luggage", "baggage", "visa") ->
                "luggage" to "#4F46E5"

            // Housing, Home & Maintenance
            matchesAny("rent", "house", "home", "housing", "society", "maintenance", "mortgage") ->
                "home" to "#2563EB"
            matchesAny("apartment", "flat", "pg", "hostel") ->
                "apartment" to "#2563EB"
            matchesAny("maid", "cleaning", "laundry", "dry clean", "housekeeping", "cook") ->
                "cleaning_services" to "#0D9488"
            matchesAny("plumb", "plumber", "plumbing", "pipe", "leak", "ro", "water purifier") ->
                "plumbing" to "#0284C7"
            matchesAny("repair", "handyman", "carpenter", "urban company", "service", "hardware", "drill") ->
                "handyman" to "#EA580C"
            matchesAny("furniture", "sofa", "chair", "table", "mattress", "ikea", "decor") ->
                "chair" to "#D97706"
            matchesAny("garden", "plant", "lawn", "nursery", "yard") ->
                "yard" to "#10B981"

            // Utilities & Bills
            matchesAny("electricity", "eb", "eb bill", "power", "tangedco", "bescom", "current", "inverter") ->
                "bolt" to "#D97706"
            matchesAny("water", "can", "metro water", "sewage") ->
                "water_drop" to "#0284C7"
            matchesAny("gas", "lpg", "cylinder", "indane", "bharatgas", "hp gas", "piped gas") ->
                "local_fire_department" to "#EA580C"
            matchesAny("wifi", "broadband", "internet", "fiber", "act fibernet", "jiofiber", "airtel xstream") ->
                "wifi" to "#2563EB"
            matchesAny("router", "modem", "data plan") ->
                "router" to "#0284C7"
            matchesAny("mobile", "phone", "recharge", "postpaid", "prepaid", "jio", "airtel", "vi", "bsnl", "sim") ->
                "phone_android" to "#4F46E5"
            matchesAny("tv", "dth", "tata play", "cable", "dish") ->
                "tv" to "#7C3AED"
            matchesAny("ac", "air condition", "air conditioner", "air conditioning", "cooling", "hvac") ->
                "ac_unit" to "#0284C7"

            // Health, Wellness & Personal Care
            matchesAny("hospital", "doctor", "clinic", "consultation", "surgery", "dental", "dentist", "eye", "lab test", "scan") ->
                "local_hospital" to "#E11D48"
            matchesAny("medicine", "pharmacy", "medical", "apollo", "medplus", "netmeds", "1mg", "tablet", "first aid") ->
                "medication" to "#059669"
            matchesAny("gym", "gyms", "fitness", "workout", "cult", "protein", "supplement") ->
                "fitness_center" to "#0D9488"
            matchesAny("yoga", "meditation", "mindfulness", "therapy", "wellness") ->
                "self_improvement" to "#10B981"
            matchesAny("salon", "haircut", "grooming", "parlour", "shave") ->
                "content_cut" to "#EC4899"
            matchesAny("spa", "massage", "skincare", "cosmetic", "beauty", "nykaa") ->
                "spa" to "#EC4899"

            // Lifestyle, Entertainment, Family & Education
            matchesAny("movie", "cinema", "pvr", "inox", "imax", "bookmyshow", "netflix", "prime video", "hotstar", "youtube", "ott", "streaming") ->
                "movie" to "#7C3AED"
            matchesAny("music", "spotify", "apple music", "concert", "audio", "podcast", "earbuds", "headphone") ->
                "headphones" to "#4F46E5"
            matchesAny("game", "gaming", "steam", "playstation", "xbox", "nintendo", "esports") ->
                "sports_esports" to "#4F46E5"
            matchesAny("sport", "cricket", "football", "badminton", "turf", "tennis", "swimming") ->
                "sports_soccer" to "#059669"
            matchesAny("party", "celebration", "festival", "diwali", "pongal", "christmas", "wedding", "event") ->
                "celebration" to "#D97706"
            matchesAny("birthday", "anniversary") ->
                "cake" to "#EC4899"
            matchesAny("school", "college", "tuition", "university", "coaching", "course", "udemy", "coursera", "exam", "fee", "fees") ->
                "school" to "#2563EB"
            matchesAny("book", "kindle", "audible", "stationery", "notebook", "library", "newspaper", "magazine") ->
                "menu_book" to "#0D9488"
            matchesAny("baby", "kid", "child", "diaper", "pediatric") ->
                "child_care" to "#EC4899"
            matchesAny("toy", "toys", "lego") ->
                "toys" to "#F97316"
            matchesAny("pet", "dog", "cat", "vet", "pet food") ->
                "pets" to "#D97706"
            matchesAny("clothes", "clothing", "apparel", "fashion", "myntra", "ajio", "zudio", "westside", "shirt", "jeans", "saree", "dress", "shoe", "footwear") ->
                "checkroom" to "#7C3AED"
            matchesAny("watch", "smartwatch", "accessory", "sunglass", "sunglasses", "bag", "backpack", "zouk") ->
                "watch" to "#334155"
            matchesAny("gold", "silver", "jewel", "jewelry", "jewellery", "grt", "tanishq", "kalyan", "malabar", "diamond", "ornament") ->
                "diamond" to "#D97706"

            // Finance, Investments, Taxes & Work
            matchesAny("sip", "mutual fund", "dsp", "elss", "invest", "investment", "stock", "equity", "zerodha", "groww", "upstox", "nifty", "etf", "gold bond") ->
                "trending_up" to "#059669"
            matchesAny("trading", "intraday", "options", "futures", "crypto", "market") ->
                "show_chart" to "#2563EB"
            matchesAny("fd", "fixed deposit", "rd", "ppf", "epf", "nps", "saving", "savings", "emergency fund") ->
                "savings" to "#10B981"
            matchesAny("insurance", "lic", "term plan", "health policy", "vehicle insurance", "premium") ->
                "security" to "#0D9488"
            matchesAny("tax", "taxes", "income tax", "gst", "tds", "property tax", "corporation tax", "legal", "ca", "challan", "fine") ->
                "gavel" to "#334155"
            matchesAny("loan", "emi", "interest paid", "debt", "settlement") ->
                "receipt_long" to "#E11D48"
            matchesAny("salary", "paycheck", "wage", "stipend") ->
                "payments" to "#059669"
            matchesAny("freelance", "consulting", "client", "contract", "side hustle") ->
                "business_center" to "#2563EB"
            matchesAny("royalty", "kdp", "publishing", "software", "tech", "laptop", "monitor", "computer", "cloud", "google play") ->
                "computer" to "#4F46E5"
            matchesAny("business", "office", "work", "coworking", "tally") ->
                "work" to "#2563EB"
            matchesAny("dividend", "interest", "yield") ->
                "percent" to "#10B981"
            matchesAny("gift", "bonus", "shagun", "present") ->
                "card_giftcard" to "#D97706"
            matchesAny("cashback", "reward", "voucher", "coupon", "refund", "reimbursement") ->
                "redeem" to "#10B981"
            matchesAny("charity", "donation", "temple", "church", "mosque", "gurudwara", "ngo", "help", "seva") ->
                "volunteer_activism" to "#10B981"
            else -> null
        }
    }
}
