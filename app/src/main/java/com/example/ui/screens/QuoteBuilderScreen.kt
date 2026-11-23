package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.MoneyFormatter
import com.example.domain.QuoteCalculator
import com.example.model.*
import com.example.pdf.QuotationPdfGenerator
import com.example.system.AppHapticsAndNotifier
import com.example.ui.AgencyUiState
import com.example.ui.components.CatalogueServiceTicketCard
import com.example.ui.components.MonochromeSearchBar
import com.example.ui.components.XMaxLogoImage
import com.example.ui.components.XMaxWordmarkBadge
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteBuilderScreen(
    state: AgencyUiState,
    initialQuote: Quotation?,
    initialRequest: ProjectRequest?,
    linkedProject: Project?,
    onBack: () -> Unit,
    onQuickSaveClient: (Client, (Client) -> Unit) -> Unit,
    onCreateRequest: (Client, String, String, List<String>, Long, Member, (ProjectRequest) -> Unit) -> Unit,
    onSaveOrIssueQuote: (Quotation, Boolean, (Quotation) -> Unit) -> Unit,
    onCreateRevision: (Quotation, (Quotation) -> Unit) -> Unit,
    onGeneratePdf: (Quotation, Boolean, (File) -> Unit) -> Unit,
    onMarkSent: (Quotation, () -> Unit) -> Unit,
    onMarkClientApproved: (Quotation, String, () -> Unit) -> Unit,
    onOpenPayments: () -> Unit = {}
) {
    val context = LocalContext.current
    val company = state.company
    val actor = state.myMember ?: return

    val canOverridePrice = state.hasPerm(AppPermission.OVERRIDE_QUOTE_PRICE)
    val canApplyDiscount = state.hasPerm(AppPermission.APPLY_DISCOUNTS)
    val canIssue = state.hasPerm(AppPermission.ISSUE_QUOTATIONS)

    var workingQuote by remember(initialQuote?.id) {
        mutableStateOf(
            initialQuote ?: Quotation(
                clientId = initialRequest?.clientId ?: linkedProject?.clientId.orEmpty(),
                requestId = initialRequest?.id.orEmpty(),
                linkedProjectId = linkedProject?.id.orEmpty(),
                projectTitle = initialRequest?.projectTitle ?: linkedProject?.let { "${it.title} — Scope Add-On" }.orEmpty(),
                scopeSummary = initialRequest?.brief.orEmpty(),
                gstEnabled = company?.gstEnabled ?: false,
                gstRatePercent = company?.gstRatePercent ?: 18,
                advancePercent = company?.defaultAdvancePercent ?: 50,
                paymentTerms = company?.defaultPaymentTerms ?: "50% advance to commence and 50% on final delivery.",
                revisionPolicy = "Includes ${company?.defaultRevisionRounds ?: 2} structured revision rounds per deliverable.",
                approvalInstructions = company?.defaultApprovalInstructions ?: "Reply in writing with quote number & version."
            )
        )
    }

    var selectedClientId by remember(workingQuote.id) {
        mutableStateOf(workingQuote.clientId.ifBlank { initialRequest?.clientId ?: linkedProject?.clientId ?: "" })
    }
    var projectTitle by remember(workingQuote.id) { mutableStateOf(workingQuote.projectTitle) }
    var scopeSummary by remember(workingQuote.id) { mutableStateOf(workingQuote.scopeSummary) }
    var lines by remember(workingQuote.id) { mutableStateOf(workingQuote.lines) }
    var urgentEnabled by remember(workingQuote.id) { mutableStateOf(workingQuote.surchargePaise > 0L) }
    var discountRupees by remember(workingQuote.id) { mutableStateOf(MoneyFormatter.paiseToEditableRupees(workingQuote.discountPaise)) }
    var gstEnabled by remember(workingQuote.id) { mutableStateOf(workingQuote.gstEnabled) }
    var advancePercentText by remember(workingQuote.id) { mutableStateOf(workingQuote.advancePercent.toString()) }
    var paymentTerms by remember(workingQuote.id) { mutableStateOf(workingQuote.paymentTerms) }
    var timelineNote by remember(workingQuote.id) { mutableStateOf(workingQuote.timelineNote.ifBlank { "10-14 business days from advance & asset handover." }) }

    var showCataloguePicker by remember { mutableStateOf(false) }
    var showQuickClientDialog by remember { mutableStateOf(false) }
    var showCustomItemDialog by remember { mutableStateOf(false) }
    var showClientApproveDialog by remember { mutableStateOf(false) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var savedFeedbackBanner by remember { mutableStateOf<String?>(null) }

    val selectedClient = state.clients.firstOrNull { it.id == selectedClientId }
    val calc = remember(lines, urgentEnabled, discountRupees, gstEnabled, advancePercentText, workingQuote.gstRatePercent) {
        QuoteCalculator.calculate(
            rawLines = lines,
            urgentDeliveryEnabled = urgentEnabled,
            urgentSurchargePercent = 25,
            discountPaise = if (canApplyDiscount) (MoneyFormatter.rupeesToPaise(discountRupees) ?: 0L) else 0L,
            gstEnabled = gstEnabled,
            gstRatePercent = workingQuote.gstRatePercent,
            advancePercent = advancePercentText.toIntOrNull()?.coerceIn(0, 100) ?: 50,
            allowUnpricedDraft = false
        )
    }

    val isEditable = workingQuote.statusEnum() == QuotationStatus.DRAFT

    fun assembleQuote(): Quotation {
        val clientSnap = selectedClient?.let {
            ClientSnapshot(it.id, it.companyName, it.contactPerson, it.phone, it.email, it.address)
        } ?: workingQuote.clientSnapshot
        return workingQuote.copy(
            clientId = selectedClient?.id ?: workingQuote.clientId,
            clientSnapshot = clientSnap,
            projectTitle = projectTitle.trim().ifBlank { "Agency Scope" },
            scopeSummary = scopeSummary.trim(),
            lines = calc.computedLines,
            subtotalPaise = calc.subtotalPaise,
            discountPaise = calc.discountPaise,
            surchargeBasePaise = calc.surchargeBasePaise,
            surchargePaise = calc.surchargePaise,
            gstEnabled = gstEnabled,
            gstAmountPaise = calc.gstAmountPaise,
            totalPaise = calc.totalPaise,
            advancePercent = advancePercentText.toIntOrNull()?.coerceIn(0, 100) ?: 50,
            advancePaise = calc.advancePaise,
            balancePaise = calc.balancePaise,
            paymentTerms = paymentTerms.trim(),
            timelineNote = timelineNote.trim(),
            separatelyBilledNote = if (calc.computedLines.any { it.separatelyBilled }) "Items marked 'Separately Billed' are paid directly by the client or billed at actuals." else ""
        )
    }

    fun ensureRequestThenSave(issueNow: Boolean, afterSave: (Quotation) -> Unit = {}) {
        val client = selectedClient ?: return
        val qDraft = assembleQuote()
        if (qDraft.requestId.isNotBlank()) {
            onSaveOrIssueQuote(qDraft, issueNow) { saved ->
                workingQuote = saved
                AppHapticsAndNotifier.triggerCompletionVibration(context)
                savedFeedbackBanner = if (issueNow) "Saved ✓ — Issued ${saved.quoteNumber}" else "Saved ✓ — Draft saved"
                afterSave(saved)
            }
        } else {
            val defaultMgr = state.members.firstOrNull { it.uid == actor.uid } ?: actor
            onCreateRequest(
                client,
                qDraft.projectTitle,
                qDraft.scopeSummary.ifBlank { qDraft.lines.joinToString { it.title } },
                qDraft.lines.map { it.title },
                System.currentTimeMillis() + 14L * 86_400_000L,
                defaultMgr
            ) { req ->
                val linked = qDraft.copy(requestId = req.id)
                onSaveOrIssueQuote(linked, issueNow) { saved ->
                    workingQuote = saved
                    AppHapticsAndNotifier.triggerCompletionVibration(context)
                    savedFeedbackBanner = if (issueNow) "Saved ✓ — Issued ${saved.quoteNumber}" else "Saved ✓ — Draft saved"
                    afterSave(saved)
                }
            }
        }
    }

    Scaffold(
        containerColor = CrispCanvas,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CrispCanvas, titleContentColor = ObsidianCharcoal),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        XMaxLogoImage(tint = ObsidianCharcoal, height = 14.dp)
                        Column {
                            Text(workingQuote.quoteNumber.ifBlank { "New Quotation" }, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                            Text("Rev v${workingQuote.revision} • ${workingQuote.statusEnum().label}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ObsidianCharcoal)
                    }
                },
                actions = {
                    if (!isEditable && canIssue) {
                        TextButton(onClick = {
                            onCreateRevision(workingQuote) { rev ->
                                workingQuote = rev
                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                savedFeedbackBanner = "Created Revision v${rev.revision}"
                            }
                        }) { Text("New Revision", color = ObsidianCharcoal, fontWeight = FontWeight.SemiBold) }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = PureWhite, border = BorderStroke(1.dp, SubtleOutline), shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Quote Total", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            Text(
                                MoneyFormatter.formatInr(calc.totalPaise),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = ObsidianCharcoal
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Advance (${advancePercentText.ifBlank { "50" }}%)", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                            Text(MoneyFormatter.formatInr(calc.advancePaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (isEditable) {
                            OutlinedButton(
                                onClick = { ensureRequestThenSave(issueNow = false) },
                                enabled = selectedClient != null,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, contentColor = ObsidianCharcoal),
                                modifier = Modifier.weight(1f).height(46.dp).testTag("save_quote_draft_button")
                            ) {
                                Text("Save Draft", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            if (canIssue) {
                                Button(
                                    onClick = { ensureRequestThenSave(issueNow = true) },
                                    enabled = selectedClient != null && calc.computedLines.isNotEmpty() && calc.validationErrors.isEmpty(),
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                    modifier = Modifier.weight(1f).height(46.dp).testTag("issue_quote_button")
                                ) {
                                    Text("Issue Quote", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val q = assembleQuote()
                                    onGeneratePdf(q, false) { file -> previewPdfFile = file }
                                },
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, contentColor = ObsidianCharcoal),
                                modifier = Modifier.weight(1f).height(46.dp).testTag("preview_pdf_button")
                            ) {
                                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Preview", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                onClick = {
                                    val q = assembleQuote()
                                    onGeneratePdf(q, true) { }
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                modifier = Modifier.weight(1f).height(46.dp).testTag("share_pdf_button")
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (workingQuote.pdfLocalPath.isBlank()) "Generate & Share" else "Share PDF",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (!isEditable && canIssue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (workingQuote.statusEnum() == QuotationStatus.ISSUED) {
                                OutlinedButton(
                                    onClick = {
                                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                                        val updated = workingQuote.copy(
                                            status = QuotationStatus.SENT.name,
                                            sentAtMillis = System.currentTimeMillis()
                                        )
                                        workingQuote = updated
                                        savedFeedbackBanner = "Saved ✓ — ${updated.quoteNumber} marked Sent to Client"
                                        onMarkSent(updated) {
                                            savedFeedbackBanner = "Saved ✓ — ${updated.quoteNumber} marked Sent to Client"
                                        }
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, SubtleOutline),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, contentColor = ObsidianCharcoal),
                                    modifier = Modifier.weight(1f).testTag("mark_quote_sent_button")
                                ) { Text("Mark Sent", fontWeight = FontWeight.SemiBold) }
                            }
                            if (workingQuote.statusEnum() != QuotationStatus.CLIENT_APPROVED && workingQuote.statusEnum() != QuotationStatus.SUPERSEDED) {
                                Button(
                                    onClick = { showClientApproveDialog = true },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = InfoPillBg, contentColor = InfoPillInk),
                                    modifier = Modifier.weight(1f).testTag("mark_client_approved_button")
                                ) { Text("Mark Client Approved", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val tfColors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PureWhite, unfocusedContainerColor = PureWhite)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(CrispCanvas)
                .padding(padding)
                .testTag("quote_builder_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Saved Feedback Notification Banner
            if (!savedFeedbackBanner.isNullOrBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SoftMint,
                        border = BorderStroke(1.dp, DeepMintInk.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth().testTag("quote_saved_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = DeepMintInk, modifier = Modifier.size(18.dp))
                            Text(
                                text = savedFeedbackBanner.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepMintInk,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { savedFeedbackBanner = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = DeepMintInk, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            // Client Approved Advance Payment Tracker Reminder
            if (workingQuote.statusEnum() == QuotationStatus.CLIENT_APPROVED && calc.advancePaise > 0L) {
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = ButterYellow,
                        border = BorderStroke(1.dp, WarmAlarmAmber.copy(alpha = 0.5f)),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().testTag("quote_advance_payment_reminder")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.Payments, contentDescription = null, tint = DeepAmberInk)
                                    Text(
                                        text = "Client Approved • Advance Due",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepAmberInk
                                    )
                                }
                                StatusPill("Advance: ${MoneyFormatter.formatInr(calc.advancePaise)}", PureWhite, DeepAmberInk)
                            }
                            Text(
                                text = "The client approved ${workingQuote.quoteNumber}. Track or record the advance deposit (${workingQuote.advancePercent}%) in the Payment Tracker to start work smoothly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ObsidianCharcoal
                            )
                            Button(
                                onClick = onOpenPayments,
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                                modifier = Modifier.fillMaxWidth().height(44.dp).testTag("quote_open_payments_button")
                            ) {
                                Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Track Advance Payment in Tracker →", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Client & Project Header Card
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MutedSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Client & Project Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                            if (isEditable) {
                                TextButton(onClick = { showQuickClientDialog = true }, modifier = Modifier.testTag("quick_add_client_button")) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp), tint = ObsidianCharcoal)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Quick Add Client", color = ObsidianCharcoal, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (state.clients.isEmpty()) {
                            Text("No clients added yet. Tap Quick Add Client to create one without losing this draft.", style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                        } else {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.clients.forEach { cli ->
                                    FilterChip(
                                        selected = cli.id == selectedClientId,
                                        onClick = { if (isEditable) selectedClientId = cli.id },
                                        shape = RoundedCornerShape(999.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ObsidianCharcoal,
                                            selectedLabelColor = PureWhite,
                                            containerColor = PureWhite,
                                            labelColor = ObsidianCharcoal
                                        ),
                                        border = null,
                                        label = { Text(cli.companyName, fontWeight = FontWeight.SemiBold) }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = projectTitle,
                            onValueChange = { projectTitle = it },
                            enabled = isEditable,
                            label = { Text("Project Title") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = tfColors,
                            modifier = Modifier.fillMaxWidth().testTag("quote_project_title_input")
                        )
                        OutlinedTextField(
                            value = scopeSummary,
                            onValueChange = { scopeSummary = it },
                            enabled = isEditable,
                            label = { Text("Scope Summary / Meeting Brief") },
                            minLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            colors = tfColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Catalogue Picker Triggers
            if (isEditable) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showCataloguePicker = true },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("open_catalogue_picker_button")
                        ) {
                            Icon(Icons.Filled.AddShoppingCart, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add From Catalogue (${state.catalogue.count { !it.archived }})", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { showCustomItemDialog = true },
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, SubtleOutline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = PureWhite, contentColor = ObsidianCharcoal),
                            modifier = Modifier.height(48.dp).testTag("add_custom_quote_item_button")
                        ) { Text("+ Custom", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

            // Validation & Double-Counting Warnings
            if (calc.overlapWarnings.isNotEmpty() || calc.validationErrors.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        calc.overlapWarnings.forEach { w ->
                            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = ButterYellow)) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = DeepAmberInk)
                                    Text(w, style = MaterialTheme.typography.bodySmall, color = DeepAmberInk)
                                }
                            }
                        }
                        calc.validationErrors.forEach { err ->
                            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = SoftBlush)) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = CrimsonBlocked)
                                    Text(err, style = MaterialTheme.typography.bodySmall, color = DeepBlushInk)
                                }
                            }
                        }
                    }
                }
            }

            // Line Items
            items(calc.computedLines, key = { it.lineId }) { line ->
                QuoteLineCard(
                    line = line,
                    editable = isEditable,
                    canOverridePrice = canOverridePrice,
                    onUpdate = { updated -> lines = lines.map { if (it.lineId == line.lineId) updated else it } },
                    onRemove = { lines = lines.filterNot { it.lineId == line.lineId } },
                    modifier = Modifier.animateItem()
                )
            }

            // Urgent Delivery, Discount, GST & Terms
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MutedSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Commercial Adjustments & Terms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        Surface(shape = RoundedCornerShape(14.dp), color = PureWhite, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text("Urgent Delivery (+25% Surcharge)", fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
                                        Text(
                                            "Applies to eligible lines (${MoneyFormatter.formatInr(calc.surchargeBasePaise)} base -> +${MoneyFormatter.formatInr(calc.surchargePaise)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = StudioTextSecondary
                                        )
                                    }
                                    Switch(checked = urgentEnabled, onCheckedChange = { if (isEditable) urgentEnabled = it })
                                }
                                HorizontalDivider(color = SubtleOutline)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Include GST (${workingQuote.gstRatePercent}%): ${MoneyFormatter.formatInr(calc.gstAmountPaise)}", color = ObsidianCharcoal, fontWeight = FontWeight.Medium)
                                    Switch(checked = gstEnabled, onCheckedChange = { if (isEditable) gstEnabled = it })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = discountRupees,
                                onValueChange = { discountRupees = it },
                                enabled = isEditable && canApplyDiscount,
                                label = { Text(if (canApplyDiscount) "Discount (₹)" else "Discount (No Perm)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = tfColors,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = advancePercentText,
                                onValueChange = { advancePercentText = it.filter(Char::isDigit).take(3) },
                                enabled = isEditable,
                                label = { Text("Advance %") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = tfColors,
                                modifier = Modifier.weight(0.7f)
                            )
                        }
                        OutlinedTextField(
                            value = paymentTerms,
                            onValueChange = { paymentTerms = it },
                            enabled = isEditable,
                            label = { Text("Payment Terms") },
                            shape = RoundedCornerShape(14.dp),
                            colors = tfColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = timelineNote,
                            onValueChange = { timelineNote = it },
                            enabled = isEditable,
                            label = { Text("Estimated Timeline") },
                            shape = RoundedCornerShape(14.dp),
                            colors = tfColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showCataloguePicker) {
        val selectedCounts = remember(lines) {
            lines.filter { it.catalogueItemId.isNotBlank() }.groupingBy { it.catalogueItemId }.eachCount()
        }
        CataloguePickerDialog(
            items = state.catalogue.filter { !it.archived },
            selectedCounts = selectedCounts,
            urgentSelected = urgentEnabled,
            onToggleItem = { item ->
                if (item.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE) {
                    urgentEnabled = !urgentEnabled
                    urgentEnabled
                } else {
                    val alreadySelected = lines.any { it.catalogueItemId == item.id }
                    if (alreadySelected) {
                        lines = lines.filterNot { it.catalogueItemId == item.id }
                        false
                    } else {
                        val newLine = QuoteCalculator.fromCatalogueItem(item, "ln_${UUID.randomUUID().toString().take(8)}")
                        lines = lines + newLine
                        true
                    }
                }
            },
            onDismiss = { showCataloguePicker = false }
        )
    }

    if (showQuickClientDialog) {
        QuickClientDialog(
            onSave = { cli ->
                onQuickSaveClient(cli) { saved ->
                    selectedClientId = saved.id
                    showQuickClientDialog = false
                }
            },
            onDismiss = { showQuickClientDialog = false }
        )
    }

    if (showCustomItemDialog) {
        CustomLineItemDialog(
            onAdd = { line ->
                lines = lines + line
                showCustomItemDialog = false
            },
            onDismiss = { showCustomItemDialog = false }
        )
    }

    if (showClientApproveDialog) {
        var approvalNote by remember { mutableStateOf("Approved via WhatsApp / Email") }
        AlertDialog(
            onDismissRequest = { showClientApproveDialog = false },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Record Client Approval", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                OutlinedTextField(
                    value = approvalNote,
                    onValueChange = { approvalNote = it },
                    label = { Text("Approval Reference / Note") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = workingQuote.copy(status = QuotationStatus.CLIENT_APPROVED.name, clientApprovalNote = approvalNote)
                        workingQuote = updated
                        AppHapticsAndNotifier.triggerCompletionVibration(context)
                        savedFeedbackBanner = "Saved ✓ — Client Approval confirmed for ${updated.quoteNumber}"
                        onMarkClientApproved(workingQuote, approvalNote) {
                            savedFeedbackBanner = "Saved ✓ — Client Approval confirmed for ${updated.quoteNumber}"
                        }
                        showClientApproveDialog = false
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Confirm Client Approval") }
            },
            dismissButton = { TextButton(onClick = { showClientApproveDialog = false }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }

    previewPdfFile?.let { file ->
        PdfPreviewDialog(
            file = file,
            quote = workingQuote,
            onShare = { QuotationPdfGenerator.launchShareSheet(context, file, workingQuote) },
            onDismiss = { previewPdfFile = null }
        )
    }
}

@Composable
private fun QuoteLineCard(
    line: QuoteLineItem,
    editable: Boolean,
    canOverridePrice: Boolean,
    onUpdate: (QuoteLineItem) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var priceInput by remember(line.lineId, line.overridePricePaise, line.unitPricePaise) {
        mutableStateOf(MoneyFormatter.paiseToEditableRupees(line.overridePricePaise ?: line.unitPricePaise))
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MutedSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.animateContentSize()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                    Text("${line.category} • ${line.pricingModeEnum().label}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary)
                }
                Text(
                    if (line.separatelyBilled) "Separately Billed" else MoneyFormatter.formatInr(line.lineTotalPaise),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianCharcoal
                )
            }

            if (line.inclusions.isNotEmpty()) {
                Text(
                    "Includes: " + line.inclusions.joinToString(" • ") { if (it.quantity > 1) "${it.quantity}× ${it.title}" else it.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            }

            if (editable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (line.quantity > 1) onUpdate(line.copy(quantity = line.quantity - 1)) }) {
                            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Decrease quantity", tint = ObsidianCharcoal)
                        }
                        Text("${line.quantity}", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                        IconButton(onClick = { onUpdate(line.copy(quantity = line.quantity + 1)) }) {
                            Icon(Icons.Filled.AddCircleOutline, contentDescription = "Increase quantity", tint = ObsidianCharcoal)
                        }
                    }

                    val canEditUnit = canOverridePrice || line.pricingModeEnum() == PricingMode.CUSTOM_QUOTE || line.pricingModeEnum() == PricingMode.ACTUAL_COST
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { txt ->
                            priceInput = txt
                            val paise = MoneyFormatter.rupeesToPaise(txt)
                            if (paise != null) {
                                onUpdate(line.copy(overridePricePaise = paise))
                            }
                        },
                        enabled = canEditUnit && !line.separatelyBilled,
                        label = { Text("Unit ₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PureWhite, unfocusedContainerColor = PureWhite),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove item", tint = CrimsonBlocked)
                    }
                }

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BillingFrequency.entries.forEach { freq ->
                        FilterChip(
                            selected = line.billingFrequencyEnum() == freq,
                            onClick = { onUpdate(line.copy(billingFrequency = freq.name)) },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ObsidianCharcoal,
                                selectedLabelColor = PureWhite,
                                containerColor = PureWhite,
                                labelColor = ObsidianCharcoal
                            ),
                            border = null,
                            label = { Text(freq.label, fontWeight = FontWeight.SemiBold) }
                        )
                    }
                    if (line.pricingModeEnum() == PricingMode.ACTUAL_COST || line.pricingModeEnum() == PricingMode.CUSTOM_QUOTE) {
                        FilterChip(
                            selected = line.separatelyBilled,
                            onClick = { onUpdate(line.copy(separatelyBilled = !line.separatelyBilled)) },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ObsidianCharcoal,
                                selectedLabelColor = PureWhite,
                                containerColor = PureWhite,
                                labelColor = ObsidianCharcoal
                            ),
                            border = null,
                            label = { Text("Separately Billed", fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CataloguePickerDialog(
    items: List<CatalogueItem>,
    selectedCounts: Map<String, Int>,
    urgentSelected: Boolean,
    onToggleItem: (CatalogueItem) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<CatalogueKind?>(null) }
    var lastActionMessage by remember { mutableStateOf<String?>(null) }
    var lastActionRemoved by remember { mutableStateOf(false) }
    var lastActionSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(lastActionSeq) {
        if (lastActionSeq > 0) {
            delay(2200L)
            lastActionMessage = null
        }
    }

    val filtered = remember(items, query, kindFilter) {
        items.filter { item ->
            val kindOk = kindFilter == null || item.kindEnum() == kindFilter
            val qOk = query.isBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                item.category.contains(query, ignoreCase = true) ||
                item.description.contains(query, ignoreCase = true)
            kindOk && qOk
        }
    }
    val totalSelected = selectedCounts.keys.size + if (urgentSelected) 1 else 0

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = CrispCanvas
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    XMaxWordmarkBadge("X.MAX Catalogue")
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        modifier = Modifier.testTag("catalogue_done_button")
                    ) {
                        Text(
                            text = if (totalSelected > 0) "Done ($totalSelected)" else "Done",
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                MonochromeSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search 40 services, 5 packages, 11 add-ons…",
                    modifier = Modifier.testTag("catalogue_search_input")
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kindFilter == null,
                        onClick = { kindFilter = null },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ObsidianCharcoal, selectedLabelColor = PureWhite, containerColor = MutedSurface, labelColor = ObsidianCharcoal),
                        border = null,
                        label = { Text("All (${items.size})", fontWeight = FontWeight.SemiBold) }
                    )
                    CatalogueKind.entries.forEach { k ->
                        FilterChip(
                            selected = kindFilter == k,
                            onClick = { kindFilter = k },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ObsidianCharcoal, selectedLabelColor = PureWhite, containerColor = MutedSurface, labelColor = ObsidianCharcoal),
                            border = null,
                            label = { Text("${k.label}s (${items.count { it.kindEnum() == k }})", fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !lastActionMessage.isNullOrBlank(),
                    enter = fadeIn(tween(180)) + expandVertically(),
                    exit = fadeOut(tween(180)) + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (lastActionRemoved) ButterYellow else SoftMint,
                        border = BorderStroke(
                            1.dp,
                            if (lastActionRemoved) WarmAlarmAmber.copy(alpha = 0.4f) else EmeraldApproved.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalogue_added_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (lastActionRemoved) Icons.Filled.RemoveCircleOutline else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (lastActionRemoved) DeepAmberInk else EmeraldApproved,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = lastActionMessage.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (lastActionRemoved) DeepAmberInk else DeepMintInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
                        val isSurcharge = item.pricingModeEnum() == PricingMode.PERCENTAGE_SURCHARGE
                        val count = if (isSurcharge) (if (urgentSelected) 1 else 0) else (selectedCounts[item.id] ?: 0).coerceAtMost(1)
                        val isSelected = count > 0

                        val handleToggle = {
                            val wasAdded = onToggleItem(item)
                            if (wasAdded) {
                                AppHapticsAndNotifier.triggerCompletionVibration(context)
                                lastActionRemoved = false
                                lastActionMessage = "Added \"${item.name}\" to quotation"
                            } else {
                                AppHapticsAndNotifier.triggerTickHaptic(context)
                                lastActionRemoved = true
                                lastActionMessage = "Removed \"${item.name}\" from quotation"
                            }
                            lastActionSeq += 1
                        }

                        CatalogueServiceTicketCard(
                            item = item,
                            index = index,
                            isSelected = isSelected,
                            selectedCount = count,
                            buttonLabel = "Select",
                            onButtonClick = handleToggle,
                            onCardClick = handleToggle,
                            modifier = Modifier
                                .animateItem()
                                .testTag("catalogue_item_${item.id}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickClientDialog(onSave: (Client) -> Unit, onDismiss: () -> Unit) {
    var companyName by remember { mutableStateOf("") }
    var contactPerson by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Quick Add Client", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = companyName, onValueChange = { companyName = it }, label = { Text("Client Company *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = contactPerson, onValueChange = { contactPerson = it }, label = { Text("Contact Person *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Meeting Notes") }, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(Client(companyName = companyName, contactPerson = contactPerson, phone = phone, email = email, meetingNotes = notes))
                },
                enabled = companyName.isNotBlank() && contactPerson.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) { Text("Save Client") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) } }
    )
}

@Composable
private fun CustomLineItemDialog(onAdd: (QuoteLineItem) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var priceRupees by remember { mutableStateOf("") }
    var separatelyBilled by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Add Custom Quote Line", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Service / Deliverable Name *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Scope Details") }, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(
                    value = priceRupees,
                    onValueChange = { priceRupees = it },
                    enabled = !separatelyBilled,
                    label = { Text("Price (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = separatelyBilled, onCheckedChange = { separatelyBilled = it })
                    Text("Mark as Separately Billed / Actual Cost", color = ObsidianCharcoal)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val paise = MoneyFormatter.rupeesToPaise(priceRupees) ?: 0L
                    onAdd(
                        QuoteLineItem(
                            lineId = "ln_${UUID.randomUUID().toString().take(8)}",
                            kind = CatalogueKind.SERVICE.name,
                            category = "Custom Scope",
                            title = title.trim(),
                            description = desc.trim(),
                            pricingMode = PricingMode.CUSTOM_QUOTE.name,
                            quantity = 1,
                            unitPricePaise = paise,
                            lineTotalPaise = if (separatelyBilled) 0L else paise,
                            separatelyBilled = separatelyBilled
                        )
                    )
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
            ) { Text("Add Line") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextSecondary) } }
    )
}

@Composable
fun PdfPreviewDialog(
    file: File,
    quote: Quotation,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val bitmaps = remember(file.absolutePath) {
        val list = mutableListOf<Bitmap>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            list += bmp
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        list
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(28.dp), color = CrispCanvas) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${quote.quoteNumber} v${quote.revision} Preview", fontWeight = FontWeight.Bold, color = ObsidianCharcoal)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onShare,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share PDF")
                        }
                        TextButton(onClick = onDismiss) { Text("Close", color = StudioTextSecondary) }
                    }
                }
                HorizontalDivider(color = SubtleOutline)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(MutedSurface),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(bitmaps) { bmp ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = PureWhite),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Quotation PDF page",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

