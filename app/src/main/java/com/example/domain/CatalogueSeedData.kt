package com.example.domain

import com.example.model.CatalogueItem
import com.example.model.CatalogueKind
import com.example.model.CompanyProfile
import com.example.model.Department
import com.example.model.JobRole
import com.example.model.PackageInclusion
import com.example.model.PricingMode
import com.example.model.RoutingRule

object CatalogueSeedData {

    const val DEFAULT_COMPANY_ID = "xmax"
    const val DEFAULT_SUPER_ADMIN_EMAIL = "xdotmax@gmail.com"
    val BOOTSTRAP_SUPER_ADMIN_EMAILS = setOf(
        DEFAULT_SUPER_ADMIN_EMAIL
    )

    fun isBootstrapEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        return BOOTSTRAP_SUPER_ADMIN_EMAILS.any { it.equals(email.trim(), ignoreCase = true) }
    }

    fun defaultCompanyProfile(createdByUid: String): CompanyProfile = CompanyProfile(
        id = DEFAULT_COMPANY_ID,
        name = "X.MAX",
        tagline = "Creative & Digital Marketing Agency",
        address = "100 Feet Road, Indiranagar, Bengaluru, Karnataka 560038",
        phone = "+91 98450 24680",
        email = "xdotmax@gmail.com",
        gstin = "",
        gstEnabled = false,
        gstRatePercent = 18,
        defaultAdvancePercent = 50,
        defaultValidityDays = 15,
        defaultRevisionRounds = 2,
        defaultPaymentTerms = "50% advance to commence project and 50% prior to final delivery.",
        defaultApprovalInstructions = "Please reply in writing referencing the Quotation Number and Revision to confirm approval.",
        nextQuoteSeq = 1L,
        seededCatalogue = true,
        createdByUid = createdByUid
    )

    fun defaultDepartments(): List<Department> = listOf(
        Department(id = "dept_creative", name = "Creative", description = "Brand identity, graphic design & social creatives", active = true),
        Department(id = "dept_web", name = "Web", description = "Websites, landing pages, UI/UX & e-commerce", active = true),
        Department(id = "dept_video", name = "Video", description = "Reels, motion graphics & video post-production", active = true),
        Department(id = "dept_marketing", name = "Marketing", description = "Performance ads, SEO, social management & analytics", active = true),
        Department(id = "dept_ops", name = "Operations", description = "Client servicing, strategy & project delivery", active = true)
    )

    fun defaultJobRoles(): List<JobRole> = listOf(
        JobRole(id = "role_graphic_designer", name = "Graphic Designer", departmentId = "dept_creative", active = true),
        JobRole(id = "role_brand_strategist", name = "Brand Strategist", departmentId = "dept_creative", active = true),
        JobRole(id = "role_web_developer", name = "Web Developer", departmentId = "dept_web", active = true),
        JobRole(id = "role_ui_ux_designer", name = "UI/UX Designer", departmentId = "dept_web", active = true),
        JobRole(id = "role_video_editor", name = "Video Editor", departmentId = "dept_video", active = true),
        JobRole(id = "role_performance_marketer", name = "Performance Marketer", departmentId = "dept_marketing", active = true),
        JobRole(id = "role_seo_specialist", name = "SEO & Content Specialist", departmentId = "dept_marketing", active = true),
        JobRole(id = "role_pm", name = "Project Manager", departmentId = "dept_ops", active = true)
    )

    fun defaultRoutingRules(): List<RoutingRule> = listOf(
        RoutingRule(
            id = "rule_creative",
            name = "Design & Branding -> Creative / Graphic Designer",
            keywords = listOf("logo", "brand", "creative", "poster", "flyer", "brochure", "packaging", "deck", "stationery", "carousel", "banner", "thumbnail", "social media creative"),
            serviceCategories = listOf("Branding & Identity", "Social Media Design", "Print & Collateral"),
            departmentId = "dept_creative",
            departmentName = "Creative",
            jobRoleId = "role_graphic_designer",
            jobRoleName = "Graphic Designer",
            priority = 90,
            active = true
        ),
        RoutingRule(
            id = "rule_video",
            name = "Reels & Motion -> Video / Video Editor",
            keywords = listOf("reel", "video", "motion", "promo", "edit", "ugc", "youtube", "podcast", "animation"),
            serviceCategories = listOf("Video & Motion"),
            departmentId = "dept_video",
            departmentName = "Video",
            jobRoleId = "role_video_editor",
            jobRoleName = "Video Editor",
            priority = 95,
            active = true
        ),
        RoutingRule(
            id = "rule_web",
            name = "Websites & Pages -> Web / Web Developer",
            keywords = listOf("website", "web", "page", "landing", "e-commerce", "shopify", "wordpress", "speed", "hosting", "domain", "maintenance", "whatsapp integration"),
            serviceCategories = listOf("Web & E-Commerce"),
            departmentId = "dept_web",
            departmentName = "Web",
            jobRoleId = "role_web_developer",
            jobRoleName = "Web Developer",
            priority = 90,
            active = true
        ),
        RoutingRule(
            id = "rule_uiux",
            name = "UI/UX Wireframes -> Web / UI/UX Designer",
            keywords = listOf("ui/ux", "wireframe", "prototype", "figma", "app design"),
            serviceCategories = listOf("UI/UX Design"),
            departmentId = "dept_web",
            departmentName = "Web",
            jobRoleId = "role_ui_ux_designer",
            jobRoleName = "UI/UX Designer",
            priority = 92,
            active = true
        ),
        RoutingRule(
            id = "rule_ads",
            name = "Ads & Funnels -> Marketing / Performance Marketer",
            keywords = listOf("meta ads", "google ads", "performance", "campaign", "retargeting", "pixel", "lead generation", "analytics", "ad spend"),
            serviceCategories = listOf("Performance Marketing"),
            departmentId = "dept_marketing",
            departmentName = "Marketing",
            jobRoleId = "role_performance_marketer",
            jobRoleName = "Performance Marketer",
            priority = 88,
            active = true
        ),
        RoutingRule(
            id = "rule_seo_content",
            name = "SEO & Copy -> Marketing / SEO & Content Specialist",
            keywords = listOf("seo", "google business", "gbp", "copy", "blog", "content calendar", "social media management", "email", "newsletter"),
            serviceCategories = listOf("SEO & Local Growth", "Content & Copywriting", "Social Media Management"),
            departmentId = "dept_marketing",
            departmentName = "Marketing",
            jobRoleId = "role_seo_specialist",
            jobRoleName = "SEO & Content Specialist",
            priority = 85,
            active = true
        )
    )

    private fun p(rupees: Long): Long = rupees * 100L

    /**
     * Complete X.MAX Starting Catalogue:
     * - 40 Individual Services (srv_01 .. srv_40)
     * - 5 One-Time Packages (pkg_01 .. pkg_05)
     * - 11 Add-Ons (add_01 .. add_11)
     */
    fun defaultCatalogue(): List<CatalogueItem> = listOf(
        // === BRANDING & IDENTITY (Services 1..7) ===
        srv("srv_01", "Branding & Identity", "Logo Design (Primary + Variations)", "3 initial concepts, vector source files, light/dark marks", p(12000), PricingMode.FIXED, false, "logo", 1),
        srv("srv_02", "Branding & Identity", "Complete Brand Identity Kit", "Logo suite, color palette, typography hierarchy, brand guidelines PDF", p(28000), PricingMode.FIXED, false, "kit", 2),
        srv("srv_03", "Branding & Identity", "Brand Refresh / Rebranding", "Modernized visual identity, updated mark & social templates", p(22000), PricingMode.STARTING_FROM, false, "project", 3),
        srv("srv_04", "Branding & Identity", "Business Stationery Suite", "Visiting card, letterhead, envelope & email signature design", p(5500), PricingMode.FIXED, false, "suite", 4),
        srv("srv_05", "Branding & Identity", "Product Packaging & Label Design", "Print-ready dieline artwork for 1 SKU with 3D mockup", p(9500), PricingMode.STARTING_FROM, false, "SKU", 5),
        srv("srv_06", "Branding & Identity", "Pitch Deck / Company Profile Design", "12-15 custom designed presentation slides with infographics", p(14000), PricingMode.FIXED, false, "deck", 6),
        srv("srv_07", "Branding & Identity", "Brand Naming & Positioning Strategy", "Market audit, naming shortlist, tagline & brand voice guide", p(16000), PricingMode.FIXED, false, "engagement", 7),

        // === SOCIAL MEDIA & GRAPHIC DESIGN (Services 8..13) ===
        srv("srv_08", "Social Media Design", "12 Social Media Creatives Pack", "12 custom branded static & carousel posts for Instagram/LinkedIn", p(10000), PricingMode.FIXED, true, "pack", 8),
        srv("srv_09", "Social Media Design", "20 Social Media Creatives Pack", "20 custom campaign creatives + story adaptations", p(16000), PricingMode.FIXED, true, "pack", 9),
        srv("srv_10", "Social Media Design", "Carousel Post Design (Up to 8 Slides)", "High-retention educational or product storytelling carousel", p(2200), PricingMode.PER_UNIT, false, "carousel", 10),
        srv("srv_11", "Social Media Design", "YouTube Thumbnail Pack (5 Thumbnails)", "Click-optimized thumbnails with custom typography", p(3500), PricingMode.FIXED, false, "pack", 11),
        srv("srv_12", "Print & Collateral", "Brochure / Menu / Catalogue Design", "Multi-page print & digital PDF layout (up to 8 pages)", p(8500), PricingMode.STARTING_FROM, false, "brochure", 12),
        srv("srv_13", "Print & Collateral", "Event Standee, Hoarding & Flyer Kit", "Print-ready large-format event & retail campaign artwork", p(6500), PricingMode.FIXED, false, "kit", 13),

        // === WEB & UI/UX (Services 14..22) ===
        srv("srv_14", "Web & E-Commerce", "5-Page Business Website", "Responsive modern website, contact form, WhatsApp CTA & basic SEO", p(28000), PricingMode.FIXED, false, "website", 14),
        srv("srv_15", "Web & E-Commerce", "10-Page Corporate Website", "Custom multi-section corporate site with CMS blog & lead capture", p(45000), PricingMode.FIXED, false, "website", 15),
        srv("srv_16", "Web & E-Commerce", "High-Converting Landing Page", "Focused single-page campaign funnel with tracking integration", p(12000), PricingMode.FIXED, false, "page", 16),
        srv("srv_17", "Web & E-Commerce", "E-Commerce Store Setup (Shopify / Woo)", "Storefront setup, up to 25 products, Razorpay/UPI & shipping rules", p(48000), PricingMode.STARTING_FROM, false, "store", 17),
        srv("srv_18", "UI/UX Design", "Web / Mobile App UI/UX Prototype", "Figma wireframes + interactive clickable UI for up to 10 screens", p(32000), PricingMode.STARTING_FROM, false, "prototype", 18),
        srv("srv_19", "Web & E-Commerce", "Website Speed & Core Web Vitals Optimization", "Image compression, caching, script audit & mobile load optimization", p(8000), PricingMode.FIXED, false, "audit", 19),
        srv("srv_20", "Web & E-Commerce", "Monthly Website Maintenance & Security", "Backups, plugin updates, uptime monitoring & minor text edits", p(4500), PricingMode.FIXED, true, "month", 20),
        srv("srv_21", "Web & E-Commerce", "Custom Web Integration / API Workflow", "Bespoke CRM webhook, booking engine, or payment workflow", p(0), PricingMode.CUSTOM_QUOTE, false, "integration", 21),
        srv("srv_22", "Web & E-Commerce", "Portfolio / Microsite Build", "Clean 3-page showcase microsite with gallery & inquiry form", p(18000), PricingMode.FIXED, false, "site", 22),

        // === VIDEO & MOTION (Services 23..28) ===
        srv("srv_23", "Video & Motion", "6 Edited Reels / Shorts Pack", "6 dynamic vertical reels with captions, B-roll & SFX", p(15000), PricingMode.FIXED, true, "pack", 23),
        srv("srv_24", "Video & Motion", "12 Edited Reels / Shorts Pack", "12 high-retention short-form edits with hooks & subtitles", p(27000), PricingMode.FIXED, true, "pack", 24),
        srv("srv_25", "Video & Motion", "Brand / Product Promo Video Edit (60-90s)", "Commercial edit with motion titles, color grading & licensed audio", p(14000), PricingMode.STARTING_FROM, false, "video", 25),
        srv("srv_26", "Video & Motion", "Motion Graphics Explainer (30-45s)", "2D animated UI/product explainer video with kinetic typography", p(18000), PricingMode.STARTING_FROM, false, "video", 26),
        srv("srv_27", "Video & Motion", "UGC Ad Video Edit (Set of 3)", "3 performance ad variations with split hooks & CTAs", p(9000), PricingMode.FIXED, false, "set", 27),
        srv("srv_28", "Video & Motion", "Podcast / YouTube Long-Form Edit", "Full episode edit, lower thirds, audio leveling & 2 short clips", p(6000), PricingMode.PER_UNIT, false, "episode", 28),

        // === PERFORMANCE MARKETING & SEO (Services 29..36) ===
        srv("srv_29", "Performance Marketing", "Meta Ads Campaign Setup & Management", "Pixel/CAPI check, audience testing, 3 ad sets & weekly optimization", p(18000), PricingMode.FIXED, true, "month", 29),
        srv("srv_30", "Performance Marketing", "Google Ads Search & Display Management", "Keyword architecture, negative lists, conversion tracking & reporting", p(18000), PricingMode.FIXED, true, "month", 30),
        srv("srv_31", "Performance Marketing", "Omnichannel Performance Retainer (Meta + Google)", "Full-funnel paid acquisition across Meta & Google with ROAS dashboard", p(30000), PricingMode.FIXED, true, "month", 31),
        srv("srv_32", "SEO & Local Growth", "Google Business Profile Setup & Local SEO", "Category optimization, geo-tagged photos, citations & review playbook", p(7500), PricingMode.FIXED, false, "profile", 32),
        srv("srv_33", "SEO & Local Growth", "On-Page & Technical SEO Retainer", "Keyword mapping, schema, metadata & monthly search growth report", p(16000), PricingMode.FIXED, true, "month", 33),
        srv("srv_34", "Performance Marketing", "WhatsApp & Lead Funnel Automation", "Automated WhatsApp greeting, lead sheet sync & follow-up drip", p(11000), PricingMode.FIXED, false, "funnel", 34),
        srv("srv_35", "Performance Marketing", "GA4, GTM & Conversion Pixel Setup", "Google Tag Manager, GA4 events, Meta Pixel & CAPI verification", p(7500), PricingMode.FIXED, false, "setup", 35),
        srv("srv_36", "Performance Marketing", "Competitor & Digital Growth Audit", "Channel audit, creative benchmarks & 90-day acquisition roadmap", p(9000), PricingMode.FIXED, false, "audit", 36),

        // === SOCIAL MEDIA MANAGEMENT & CONTENT (Services 37..40) ===
        srv("srv_37", "Social Media Management", "Social Media Account Management (2 Channels)", "Content calendar, scheduling, captions, hashtags & monthly insights", p(15000), PricingMode.FIXED, true, "month", 37),
        srv("srv_38", "Content & Copywriting", "Website Conversion Copywriting (Up to 5 Pages)", "Research-backed brand messaging, headlines, CTAs & SEO meta copy", p(12000), PricingMode.FIXED, false, "website", 38),
        srv("srv_39", "Content & Copywriting", "SEO Blog Articles Pack (4 Articles)", "4 researched 1,000-word articles formatted with internal links", p(10000), PricingMode.FIXED, true, "pack", 39),
        srv("srv_40", "Content & Copywriting", "Email Marketing & Newsletter Flows (4 Campaigns)", "4 responsive email templates, copy & audience segmentation setup", p(9500), PricingMode.FIXED, true, "pack", 40),

        // === 5 ONE-TIME PACKAGES (pkg_01 .. pkg_05) ===
        CatalogueItem(
            id = "pkg_01",
            kind = CatalogueKind.PACKAGE.name,
            category = "One-Time Packages",
            name = "Starter Brand Kit",
            description = "Essential visual identity for new businesses launching fast.",
            pricePaise = p(22000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_01", "Logo design (Primary + Variations)", "Branding & Identity", 1, "3 initial concepts & final vector files"),
                PackageInclusion("srv_04", "Business stationery suite", "Branding & Identity", 1, "Visiting card, letterhead & email signature"),
                PackageInclusion("srv_08", "6 Social media launch creatives", "Social Media Design", 6, "Branded launch templates for Instagram/LinkedIn")
            ),
            sortOrder = 101
        ),
        CatalogueItem(
            id = "pkg_02",
            kind = CatalogueKind.PACKAGE.name,
            category = "One-Time Packages",
            name = "Social Launch Pack",
            description = "High-impact creative & reel sprint to build social momentum.",
            pricePaise = p(32000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_08", "12 Social media creatives", "Social Media Design", 12, "Static & carousel creatives"),
                PackageInclusion("srv_23", "6 Edited reels", "Video & Motion", 6, "Short-form reels with captions & hooks"),
                PackageInclusion("srv_37", "Content calendar & caption copy", "Social Media Management", 1, "30-day launch calendar & copywriting")
            ),
            sortOrder = 102
        ),
        CatalogueItem(
            id = "pkg_03",
            kind = CatalogueKind.PACKAGE.name,
            category = "One-Time Packages",
            name = "Business Launch",
            description = "Complete brand, website & local growth launch bundle for growing brands.",
            pricePaise = p(55000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_01", "Logo design", "Branding & Identity", 1, "Primary logo, monochrome mark & brand guidelines"),
                PackageInclusion("srv_14", "5-page business website", "Web & E-Commerce", 1, "Responsive 5-page website with lead form & WhatsApp CTA"),
                PackageInclusion("srv_08", "12 Social media creatives", "Social Media Design", 12, "Branded social launch grid & story templates"),
                PackageInclusion("srv_32", "Google Business Profile setup", "SEO & Local Growth", 1, "Verified GBP listing & local SEO checklist")
            ),
            sortOrder = 103
        ),
        CatalogueItem(
            id = "pkg_04",
            kind = CatalogueKind.PACKAGE.name,
            category = "One-Time Packages",
            name = "Performance Growth Sprint",
            description = "Paid acquisition, landing funnel & video ad creative testing sprint.",
            pricePaise = p(48000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_16", "High-converting landing page", "Web & E-Commerce", 1, "Dedicated campaign funnel page"),
                PackageInclusion("srv_29", "Meta Ads campaign setup & launch", "Performance Marketing", 1, "Campaign architecture & audience testing"),
                PackageInclusion("srv_27", "3 UGC video ad variations", "Video & Motion", 3, "Hook variations for A/B testing"),
                PackageInclusion("srv_35", "GA4 & Meta Pixel / CAPI setup", "Performance Marketing", 1, "End-to-end conversion tracking")
            ),
            sortOrder = 104
        ),
        CatalogueItem(
            id = "pkg_05",
            kind = CatalogueKind.PACKAGE.name,
            category = "One-Time Packages",
            name = "E-Commerce Launch Pro",
            description = "End-to-end D2C storefront, packaging & conversion launch kit.",
            pricePaise = p(75000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_02", "Complete brand identity kit", "Branding & Identity", 1, "Logo, palette, fonts & brand manual"),
                PackageInclusion("srv_17", "E-Commerce store setup (up to 25 SKUs)", "Web & E-Commerce", 1, "Payment gateway, shipping & collection pages"),
                PackageInclusion("srv_08", "12 Social media creatives", "Social Media Design", 12, "Product spotlight & offer creatives"),
                PackageInclusion("srv_35", "Conversion tracking & analytics", "Performance Marketing", 1, "Purchase events & Google Analytics 4")
            ),
            sortOrder = 105
        ),

        // === 11 ADD-ONS (add_01 .. add_11) ===
        CatalogueItem(
            id = "add_01",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Extra Page",
            description = "Additional custom designed & responsive website page beyond package limit.",
            pricePaise = p(2000),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "page",
            sortOrder = 201
        ),
        CatalogueItem(
            id = "add_02",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Urgent Delivery (25% Express Surcharge)",
            description = "Priority studio turnaround calculated as 25% of eligible creative/web lines.",
            pricePaise = 0L,
            pricingMode = PricingMode.PERCENTAGE_SURCHARGE.name,
            surchargePercent = 25,
            unitLabel = "surcharge",
            sortOrder = 202
        ),
        CatalogueItem(
            id = "add_03",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Media Ad Spend Budget (Pass-Through)",
            description = "Direct Meta / Google ad budget billed directly or at actual platform cost.",
            pricePaise = 0L,
            pricingMode = PricingMode.ACTUAL_COST.name,
            requiresBillingFrequency = true,
            unitLabel = "campaign",
            sortOrder = 203
        ),
        CatalogueItem(
            id = "add_04",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Additional Revision Round",
            description = "Extra structured revision round beyond the standard 2 included rounds.",
            pricePaise = p(2500),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "round",
            sortOrder = 204
        ),
        CatalogueItem(
            id = "add_05",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Extra Social Creative",
            description = "Single additional static or carousel graphic added to campaign pack.",
            pricePaise = p(900),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "creative",
            sortOrder = 205
        ),
        CatalogueItem(
            id = "add_06",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Extra Edited Reel",
            description = "Additional 30-60s vertical reel edit with subtitles & motion callouts.",
            pricePaise = p(2800),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "reel",
            sortOrder = 206
        ),
        CatalogueItem(
            id = "add_07",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "WhatsApp Chat & Lead Widget Integration",
            description = "Floating WhatsApp widget with pre-filled inquiry & UTM capture.",
            pricePaise = p(1500),
            pricingMode = PricingMode.FIXED.name,
            unitLabel = "setup",
            sortOrder = 207
        ),
        CatalogueItem(
            id = "add_08",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Multi-Language Page Adaptation",
            description = "Localized layout adaptation per additional regional language.",
            pricePaise = p(3500),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "language",
            sortOrder = 208
        ),
        CatalogueItem(
            id = "add_09",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Domain, SSL & Hosting Setup",
            description = "DNS configuration, SSL certificate & cloud hosting deployment (domain cost at actuals).",
            pricePaise = p(3000),
            pricingMode = PricingMode.FIXED.name,
            unitLabel = "setup",
            sortOrder = 209
        ),
        CatalogueItem(
            id = "add_10",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Licensed Stock Assets / Font License",
            description = "Commercial stock video, photography, or premium font licensing.",
            pricePaise = 0L,
            pricingMode = PricingMode.ACTUAL_COST.name,
            unitLabel = "license",
            sortOrder = 210
        ),
        CatalogueItem(
            id = "add_11",
            kind = CatalogueKind.ADDON.name,
            category = "Add-Ons",
            name = "Bespoke Feature / Custom Quote Add-On",
            description = "Custom scope priced specifically to client requirements.",
            pricePaise = 0L,
            pricingMode = PricingMode.CUSTOM_QUOTE.name,
            unitLabel = "custom",
            sortOrder = 211
        )
    )

    private fun srv(
        id: String,
        category: String,
        name: String,
        description: String,
        pricePaise: Long,
        mode: PricingMode,
        recurring: Boolean,
        unitLabel: String,
        sortOrder: Int
    ) = CatalogueItem(
        id = id,
        kind = CatalogueKind.SERVICE.name,
        category = category,
        name = name,
        description = description,
        pricePaise = pricePaise,
        pricingMode = mode.name,
        requiresBillingFrequency = recurring,
        unitLabel = unitLabel,
        sortOrder = sortOrder
    )
}
