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

    const val OFFICIAL_CATALOGUE_VERSION = "2026_02"

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
        defaultPaymentTerms = "50% advance to begin work and 50% before final delivery. GST extra if applicable; advertising budget & third-party expenses billed separately.",
        defaultApprovalInstructions = "Quotation valid for 15 days. Please reply in writing referencing the Quotation Number and Revision to confirm approval.",
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
            keywords = listOf(
                "logo", "brand", "creative", "poster", "flyer", "brochure", "menu",
                "infographic", "packaging", "stationery", "business card", "letterhead",
                "carousel", "story", "thumbnail", "social media post", "guidelines"
            ),
            serviceCategories = listOf("Graphic Design", "Branding & Identity", "Social Media Design", "Print & Collateral"),
            departmentId = "dept_creative",
            departmentName = "Creative",
            jobRoleId = "role_graphic_designer",
            jobRoleName = "Graphic Designer",
            priority = 90,
            active = true
        ),
        RoutingRule(
            id = "rule_video",
            name = "Reels & Video -> Video / Video Editor",
            keywords = listOf(
                "reel", "video", "motion", "promo", "promotional video", "edit",
                "youtube", "product video", "short-form", "animation"
            ),
            serviceCategories = listOf("Video Production & Editing", "Video & Motion"),
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
            keywords = listOf(
                "website", "web", "page", "landing", "e-commerce", "portfolio",
                "redesign", "hosting", "domain", "maintenance", "chat integration"
            ),
            serviceCategories = listOf("Website Development", "Web & E-Commerce"),
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
            keywords = listOf(
                "meta ads", "google ads", "performance", "campaign", "retargeting",
                "pixel", "lead generation", "analytics", "ad spend", "platform setup"
            ),
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
            name = "Digital Marketing & SEO -> Marketing / SEO Specialist",
            keywords = listOf(
                "seo", "keyword", "competitor", "google business", "gbp", "copy",
                "content calendar", "content strategy", "social media account",
                "social media strategy", "digital marketing strategy"
            ),
            serviceCategories = listOf("Digital Marketing", "SEO & Local Growth", "Content & Copywriting", "Social Media Management"),
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
     * Official X.MAX Service & Pricing Catalogue (2026 Updated PDF):
     * - 40 Individual Services (srv_01 .. srv_40)
     * - 5 Bundled Project Packages (pkg_01 .. pkg_05)
     * - 11 Additional Charges / Add-Ons (add_01 .. add_11)
     */
    fun defaultCatalogue(): List<CatalogueItem> = listOf(
        // === 1. GRAPHIC DESIGN (srv_01 .. srv_10) ===
        srv("srv_01", "Graphic Design", "Social media post", "Custom branded static social media post designed for Instagram, Facebook, or LinkedIn feeds.", p(500), PricingMode.FIXED, false, "post", 1),
        srv("srv_02", "Graphic Design", "Instagram story", "Vertical 9:16 Instagram story creative crafted for offers, updates, and daily engagement.", p(300), PricingMode.FIXED, false, "story", 2),
        srv("srv_03", "Graphic Design", "Carousel (up to 5 slides)", "Multi-slide swipeable carousel (up to 5 slides) designed for storytelling, product highlights, or education.", p(1500), PricingMode.FIXED, false, "carousel", 3),
        srv("srv_04", "Graphic Design", "Promotional poster", "High-impact promotional poster layout suitable for digital announcements or print campaigns.", p(1000), PricingMode.FIXED, false, "poster", 4),
        srv("srv_05", "Graphic Design", "Advertising creative", "Conversion-focused ad graphic engineered for paid Meta, Google Display, or sponsored placements.", p(750), PricingMode.FIXED, false, "creative", 5),
        srv("srv_06", "Graphic Design", "YouTube thumbnail", "Click-optimized YouTube thumbnail featuring clear typography and strong visual contrast.", p(500), PricingMode.FIXED, false, "thumbnail", 6),
        srv("srv_07", "Graphic Design", "Flyer design", "Print-ready single or double-sided promotional flyer with clean hierarchy and brand consistency.", p(1500), PricingMode.FIXED, false, "flyer", 7),
        srv("srv_08", "Graphic Design", "Brochure design", "Structured multi-fold or multi-page company/product brochure formatted for print and digital sharing.", p(3000), PricingMode.FIXED, false, "brochure", 8),
        srv("srv_09", "Graphic Design", "Menu design", "Clean restaurant, café, or service menu design ready for print production and digital QR viewing.", p(2000), PricingMode.FIXED, false, "menu", 9),
        srv("srv_10", "Graphic Design", "Infographic", "Visual data, process, or comparison infographic designed for presentations and social sharing.", p(1500), PricingMode.FIXED, false, "infographic", 10),

        // === 2. BRANDING & IDENTITY (srv_11 .. srv_18) ===
        srv("srv_11", "Branding & Identity", "Logo design", "Custom primary logo design with original concepts, refinements, and scalable final vector exports.", p(5000), PricingMode.FIXED, false, "logo", 11),
        srv("srv_12", "Branding & Identity", "Logo redesign", "Modernized refresh of your existing logo mark for sharper proportions and digital clarity.", p(3500), PricingMode.FIXED, false, "logo", 12),
        srv("srv_13", "Branding & Identity", "Brand identity", "Complete brand identity system covering logo suite, colour palette, typography, and visual styling.", p(15000), PricingMode.FIXED, false, "system", 13),
        srv("srv_14", "Branding & Identity", "Business card", "Print-ready double-sided business card layout tailored to your brand identity.", p(1000), PricingMode.FIXED, false, "design", 14),
        srv("srv_15", "Branding & Identity", "Letterhead", "Branded official company letterhead template delivered in print PDF and editable document formats.", p(1000), PricingMode.FIXED, false, "design", 15),
        srv("srv_16", "Branding & Identity", "Brand guidelines", "Detailed brand usage manual documenting logo clearspace, colour codes, typography, and layout rules.", p(5000), PricingMode.FIXED, false, "guide", 16),
        srv("srv_17", "Branding & Identity", "Packaging design", "Custom packaging and label dieline design (₹5,000 onwards depending on SKU format and print finishes).", p(5000), PricingMode.STARTING_FROM, false, "SKU", 17),
        srv("srv_18", "Branding & Identity", "Complete stationery", "Coordinated corporate stationery suite including business card, letterhead, envelope, and folder.", p(4000), PricingMode.FIXED, false, "suite", 18),

        // === 3. VIDEO PRODUCTION & EDITING (srv_19 .. srv_25) ===
        srv("srv_19", "Video Production & Editing", "Basic reel editing", "Clean vertical reel editing with smooth cuts, beat sync, basic captions, and balanced audio.", p(1500), PricingMode.FIXED, false, "reel", 19),
        srv("srv_20", "Video Production & Editing", "Advanced reel editing", "High-retention vertical reel edit with kinetic captions, motion graphics, sound design, and colour grading.", p(3000), PricingMode.FIXED, false, "reel", 20),
        srv("srv_21", "Video Production & Editing", "Motion graphics video", "Custom 2D animated motion graphics, product callouts, or kinetic typography explainer video.", p(4000), PricingMode.FIXED, false, "video", 21),
        srv("srv_22", "Video Production & Editing", "Promotional video", "Commercial brand or product promo edit with motion titles, colour grading, and licensed background music.", p(5000), PricingMode.FIXED, false, "video", 22),
        srv("srv_23", "Video Production & Editing", "YouTube video editing", "Long-form YouTube episode editing with pacing, lower thirds, B-roll, and audio cleanup (₹3,000 onwards).", p(3000), PricingMode.STARTING_FROM, false, "video", 23),
        srv("srv_24", "Video Production & Editing", "Product video editing", "Focused product showcase video highlight with feature callouts, clean transitions, and sound design.", p(3000), PricingMode.FIXED, false, "video", 24),
        srv("srv_25", "Video Production & Editing", "Short-form ad video", "Performance-structured short ad edit engineered with an opening hook, product demo, and CTA.", p(3000), PricingMode.FIXED, false, "video", 25),

        // === 4. WEBSITE DEVELOPMENT (srv_26 .. srv_31) ===
        srv("srv_26", "Website Development", "Landing page", "Single-page responsive landing page built for high conversion, inquiry capture, and fast mobile loading.", p(8000), PricingMode.FIXED, false, "page", 26),
        srv("srv_27", "Website Development", "Portfolio website", "Clean responsive portfolio showcase website with work gallery, about sections, and contact form.", p(15000), PricingMode.FIXED, false, "website", 27),
        srv("srv_28", "Website Development", "Business website", "Multi-page responsive business website (up to 5 pages) with lead capture, Direct Chat CTA, and basic SEO.", p(20000), PricingMode.FIXED, false, "website", 28),
        srv("srv_29", "Website Development", "E-commerce website", "Full online store with product catalog, cart, checkout flow, shipping setup, and payment gateway integration.", p(35000), PricingMode.FIXED, false, "store", 29),
        srv("srv_30", "Website Development", "Website redesign", "Modern UI/UX and speed overhaul for an existing website (₹10,000 onwards depending on page count).", p(10000), PricingMode.STARTING_FROM, false, "website", 30),
        srv("srv_31", "Website Development", "Website maintenance", "Monthly backups, security patches, uptime monitoring, and minor content updates (₹2,000 onwards).", p(2000), PricingMode.STARTING_FROM, true, "month", 31),

        // === 5. DIGITAL MARKETING (srv_32 .. srv_40) ===
        srv("srv_32", "Digital Marketing", "Social media account setup", "Account creation, bio optimization, profile branding, story highlights, and link-in-bio setup.", p(2000), PricingMode.FIXED, false, "setup", 32),
        srv("srv_33", "Digital Marketing", "Social media strategy", "Tailored content pillars, target audience positioning, posting cadence, and growth roadmap.", p(3000), PricingMode.FIXED, false, "strategy", 33),
        srv("srv_34", "Digital Marketing", "Content calendar", "Structured monthly content calendar mapping topics, creative formats, hooks, and publishing dates.", p(2000), PricingMode.FIXED, false, "calendar", 34),
        srv("srv_35", "Digital Marketing", "Meta Ads campaign setup", "Meta Business Suite setup, Pixel/conversion tracking, audience structure, and initial ad campaign launch.", p(5000), PricingMode.FIXED, false, "setup", 35),
        srv("srv_36", "Digital Marketing", "Google Ads campaign setup", "Google Ads Search/Display campaign architecture, keyword targeting, conversion tags, and launch.", p(5000), PricingMode.FIXED, false, "setup", 36),
        srv("srv_37", "Digital Marketing", "SEO audit", "Comprehensive website SEO health check covering indexing, technical errors, Core Web Vitals, and fixes.", p(3000), PricingMode.FIXED, false, "audit", 37),
        srv("srv_38", "Digital Marketing", "Keyword research", "Commercial & informational keyword mapping report with search intent and priority ranking targets.", p(2000), PricingMode.FIXED, false, "report", 38),
        srv("srv_39", "Digital Marketing", "On-page SEO", "Optimization of meta titles, descriptions, headings, schema markup, and internal links (₹5,000 onwards).", p(5000), PricingMode.STARTING_FROM, false, "website", 39),
        srv("srv_40", "Digital Marketing", "Competitor analysis", "Detailed benchmark analysis of competitor positioning, creatives, keywords, and paid funnel angles.", p(3000), PricingMode.FIXED, false, "report", 40),

        // === 02. OPTIONAL BUNDLED PROJECT PACKAGES (pkg_01 .. pkg_05) ===
        CatalogueItem(
            id = "pkg_01",
            kind = CatalogueKind.PACKAGE.name,
            category = "Bundled Packages",
            name = "BRAND ESSENTIALS",
            description = "For startups establishing their brand identity. Includes: Logo design with 2 concepts • Logo variations • Brand colour palette • Typography selection • Business card design • 3 social media creatives • Basic brand guidelines.",
            pricePaise = p(15000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_11", "Logo design with 2 concepts", "Branding & Identity", 1, "2 initial logo concepts & refinements"),
                PackageInclusion("srv_11", "Logo variations", "Branding & Identity", 1, "Primary, stacked, icon & monochrome marks"),
                PackageInclusion("srv_13", "Brand colour palette", "Branding & Identity", 1, "Primary & secondary colour system"),
                PackageInclusion("srv_13", "Typography selection", "Branding & Identity", 1, "Brand display & body font pairings"),
                PackageInclusion("srv_14", "Business card design", "Branding & Identity", 1, "Print-ready double-sided card layout"),
                PackageInclusion("srv_01", "3 social media creatives", "Graphic Design", 3, "Branded social launch creatives"),
                PackageInclusion("srv_16", "Basic brand guidelines", "Branding & Identity", 1, "Essential visual usage reference")
            ),
            sortOrder = 101
        ),
        CatalogueItem(
            id = "pkg_02",
            kind = CatalogueKind.PACKAGE.name,
            category = "Bundled Packages",
            name = "BRAND BUILDER",
            description = "For businesses building a professional brand image. Includes: Complete logo design • Brand identity system • Brand guidelines • Business card and letterhead • 5 social media creatives • 2 promotional posters • 2 reel edits.",
            pricePaise = p(25000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_11", "Complete logo design", "Branding & Identity", 1, "Full logo suite with vector source files"),
                PackageInclusion("srv_13", "Brand identity system", "Branding & Identity", 1, "Coordinated colours, typography & visual language"),
                PackageInclusion("srv_16", "Brand guidelines", "Branding & Identity", 1, "Comprehensive brand guideline PDF"),
                PackageInclusion("srv_18", "Business card and letterhead", "Branding & Identity", 1, "Print-ready card & official letterhead"),
                PackageInclusion("srv_01", "5 social media creatives", "Graphic Design", 5, "Custom branded social posts"),
                PackageInclusion("srv_04", "2 promotional posters", "Graphic Design", 2, "Campaign promotional poster designs"),
                PackageInclusion("srv_19", "2 reel edits", "Video Production & Editing", 2, "Edited vertical short-form reels")
            ),
            sortOrder = 102
        ),
        CatalogueItem(
            id = "pkg_03",
            kind = CatalogueKind.PACKAGE.name,
            category = "Bundled Packages",
            name = "DIGITAL PRESENCE",
            description = "For businesses establishing their online presence. Includes: Complete brand identity • 8 social media creatives • 4 professionally edited reels • Social media account setup • Content strategy • Landing page design and development • Basic on-page SEO.",
            pricePaise = p(40000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_13", "Complete brand identity", "Branding & Identity", 1, "Logo suite, palette, typography & assets"),
                PackageInclusion("srv_01", "8 social media creatives", "Graphic Design", 8, "Branded static & feed creatives"),
                PackageInclusion("srv_20", "4 professionally edited reels", "Video Production & Editing", 4, "Dynamic vertical reels with captions & SFX"),
                PackageInclusion("srv_32", "Social media account setup", "Digital Marketing", 1, "Optimized profiles, bio & highlights"),
                PackageInclusion("srv_33", "Content strategy", "Digital Marketing", 1, "Content pillars & publishing plan"),
                PackageInclusion("srv_26", "Landing page design and development", "Website Development", 1, "Responsive high-converting landing page"),
                PackageInclusion("srv_39", "Basic on-page SEO", "Digital Marketing", 1, "Metadata, headings & indexing setup")
            ),
            sortOrder = 103
        ),
        CatalogueItem(
            id = "pkg_04",
            kind = CatalogueKind.PACKAGE.name,
            category = "Bundled Packages",
            name = "BUSINESS LAUNCH",
            description = "For businesses launching or expanding digitally. Includes: Complete brand identity • 12 social media creatives • 6 edited reels • 4 advertising creatives • Social media account setup • Business website (up to 5 pages) • Basic SEO setup • Meta Ads campaign setup • Digital marketing strategy.",
            pricePaise = p(55000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_13", "Complete brand identity", "Branding & Identity", 1, "Full visual identity system & guidelines"),
                PackageInclusion("srv_01", "12 social media creatives", "Graphic Design", 12, "Branded social launch creatives"),
                PackageInclusion("srv_19", "6 edited reels", "Video Production & Editing", 6, "Engaging short-form reels with captions"),
                PackageInclusion("srv_05", "4 advertising creatives", "Graphic Design", 4, "Conversion-ready paid ad creatives"),
                PackageInclusion("srv_32", "Social media account setup", "Digital Marketing", 1, "Profile branding & optimization"),
                PackageInclusion("srv_28", "Business website (up to 5 pages)", "Website Development", 1, "Responsive 5-page business website"),
                PackageInclusion("srv_39", "Basic SEO setup", "Digital Marketing", 1, "On-page SEO & search indexing setup"),
                PackageInclusion("srv_35", "Meta Ads campaign setup", "Digital Marketing", 1, "Pixel, audiences & launch campaign"),
                PackageInclusion("srv_33", "Digital marketing strategy", "Digital Marketing", 1, "Growth strategy & acquisition roadmap")
            ),
            sortOrder = 104
        ),
        CatalogueItem(
            id = "pkg_05",
            kind = CatalogueKind.PACKAGE.name,
            category = "Bundled Packages",
            name = "COMPLETE BRAND EXPERIENCE",
            description = "For businesses seeking a comprehensive branding and digital launch. Includes: Complete brand identity • 15 social media creatives • 8 advanced reels • 5 advertising creatives • 5-page business website • On-page SEO setup • Social media account setup • Meta Ads campaign setup • Google Ads campaign setup • Competitor analysis • Digital marketing strategy.",
            pricePaise = p(75000),
            pricingMode = PricingMode.FIXED.name,
            requiresBillingFrequency = false,
            unitLabel = "package",
            inclusions = listOf(
                PackageInclusion("srv_13", "Complete brand identity", "Branding & Identity", 1, "Complete brand system & usage guidelines"),
                PackageInclusion("srv_01", "15 social media creatives", "Graphic Design", 15, "Comprehensive social launch pack"),
                PackageInclusion("srv_20", "8 advanced reels", "Video Production & Editing", 8, "Motion graphics, SFX & colour-graded reels"),
                PackageInclusion("srv_05", "5 advertising creatives", "Graphic Design", 5, "Multi-format paid campaign ad creatives"),
                PackageInclusion("srv_28", "5-page business website", "Website Development", 1, "Full 5-page responsive business website"),
                PackageInclusion("srv_39", "On-page SEO setup", "Digital Marketing", 1, "Keywords, metadata, schema & on-page SEO"),
                PackageInclusion("srv_32", "Social media account setup", "Digital Marketing", 1, "Full profile optimization & setup"),
                PackageInclusion("srv_35", "Meta Ads campaign setup", "Digital Marketing", 1, "Meta Business, Pixel & launch ads"),
                PackageInclusion("srv_36", "Google Ads campaign setup", "Digital Marketing", 1, "Google Search/Display campaign launch"),
                PackageInclusion("srv_40", "Competitor analysis", "Digital Marketing", 1, "Market & competitor benchmark audit"),
                PackageInclusion("srv_33", "Digital marketing strategy", "Digital Marketing", 1, "Full-funnel digital launch strategy")
            ),
            sortOrder = 105
        ),

        // === 03. ADDITIONAL CHARGES / ADD-ONS (add_01 .. add_11) ===
        CatalogueItem(
            id = "add_01",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Additional design concept",
            description = "Extra original design direction or concept option beyond standard package inclusions.",
            pricePaise = p(1000),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "concept",
            sortOrder = 201
        ),
        CatalogueItem(
            id = "add_02",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Urgent delivery (25% extra)",
            description = "Priority studio turnaround calculated as 25% extra on eligible project lines.",
            pricePaise = 0L,
            pricingMode = PricingMode.PERCENTAGE_SURCHARGE.name,
            surchargePercent = 25,
            unitLabel = "surcharge",
            sortOrder = 202
        ),
        CatalogueItem(
            id = "add_03",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Additional revision round",
            description = "Extra revision cycle beyond the standard 2 included rounds per major deliverable (₹500 onwards).",
            pricePaise = p(500),
            pricingMode = PricingMode.STARTING_FROM.name,
            unitLabel = "round",
            sortOrder = 203
        ),
        CatalogueItem(
            id = "add_04",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Source / editable files",
            description = "Open native working source files (AI, PSD, Figma, PRPROJ) supplied upon completion (₹1,000 onwards).",
            pricePaise = p(1000),
            pricingMode = PricingMode.STARTING_FROM.name,
            unitLabel = "deliverable",
            sortOrder = 204
        ),
        CatalogueItem(
            id = "add_05",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Premium stock assets",
            description = "Licensed commercial stock photography, video clips, audio, or fonts billed at actual cost.",
            pricePaise = 0L,
            pricingMode = PricingMode.ACTUAL_COST.name,
            unitLabel = "asset",
            sortOrder = 205
        ),
        CatalogueItem(
            id = "add_06",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Professional photography",
            description = "On-location or studio commercial photography shoot priced by custom quotation.",
            pricePaise = 0L,
            pricingMode = PricingMode.CUSTOM_QUOTE.name,
            unitLabel = "shoot",
            sortOrder = 206
        ),
        CatalogueItem(
            id = "add_07",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Professional videography",
            description = "On-location video production crew, lighting, and camera shoot priced by custom quotation.",
            pricePaise = 0L,
            pricingMode = PricingMode.CUSTOM_QUOTE.name,
            unitLabel = "shoot",
            sortOrder = 207
        ),
        CatalogueItem(
            id = "add_08",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Additional website page",
            description = "Extra custom designed and responsive website page added beyond website package limit.",
            pricePaise = p(2000),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "page",
            sortOrder = 208
        ),
        CatalogueItem(
            id = "add_09",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Website hosting / domain",
            description = "Third-party domain registration and web hosting plan billed separately at actual cost.",
            pricePaise = 0L,
            pricingMode = PricingMode.ACTUAL_COST.name,
            unitLabel = "year",
            sortOrder = 209
        ),
        CatalogueItem(
            id = "add_10",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Ad campaign management",
            description = "Ongoing ad performance monitoring, optimization, and reporting (₹5,000 onwards; ad budget not included).",
            pricePaise = p(5000),
            pricingMode = PricingMode.STARTING_FROM.name,
            requiresBillingFrequency = true,
            unitLabel = "month",
            sortOrder = 210
        ),
        CatalogueItem(
            id = "add_11",
            kind = CatalogueKind.ADDON.name,
            category = "Additional Charges",
            name = "Additional platform setup",
            description = "Profile creation, branding banners, and bio setup for an additional social or ad channel.",
            pricePaise = p(1000),
            pricingMode = PricingMode.PER_UNIT.name,
            unitLabel = "platform",
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
