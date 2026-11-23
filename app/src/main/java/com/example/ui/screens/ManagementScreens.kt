package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.MoneyFormatter
import com.example.model.*
import com.example.ui.AgencyUiState
import com.example.ui.components.CatalogueServiceTicketCard
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MetricStatCard
import com.example.ui.components.MonochromeSearchBar
import com.example.ui.components.StatusPill
import com.example.ui.theme.*

@Composable
fun ClientsScreen(
    state: AgencyUiState,
    onSaveClient: (Client) -> Unit,
    onDeleteClient: (Client) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var editingClient by remember { mutableStateOf<Client?>(null) }
    var deleteConfirmClient by remember { mutableStateOf<Client?>(null) }
    val canEdit = state.hasPerm(AppPermission.MANAGE_CLIENTS) || state.hasPerm(AppPermission.CREATE_QUOTATIONS)
    val canDeleteClient = state.isSuperAdmin

    val filtered = remember(state.clients, query) {
        state.clients.filter {
            query.isBlank() ||
                it.companyName.contains(query, ignoreCase = true) ||
                it.contactPerson.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Clients (${filtered.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (canEdit) {
                    Button(
                        onClick = { editingClient = Client() },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.height(42.dp).testTag("add_client_button")
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ADD CLIENT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search client company or contact…"
            )
        }
        if (filtered.isEmpty()) {
            item { EmptyStateCard(Icons.Filled.Contacts, "NO CLIENTS FOUND", "Add your first client during a meeting or from here.") }
        } else {
            items(filtered, key = { it.id }) { cli ->
                Surface(
                    onClick = { if (canEdit) editingClient = cli },
                    shape = RoundedCornerShape(22.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(KpiSkyBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Business, contentDescription = null, tint = KpiSkyStroke, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(cli.companyName.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                            Text("${cli.contactPerson.uppercase()} • ${cli.phone.ifBlank { cli.email.ifBlank { "No contact" } }}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                            if (cli.meetingNotes.isNotBlank()) {
                                Text("Notes: ${cli.meetingNotes}", style = MaterialTheme.typography.labelSmall, color = StudioTextSecondary, maxLines = 2)
                            }
                        }
                        if (canDeleteClient) {
                            IconButton(
                                onClick = { deleteConfirmClient = cli },
                                modifier = Modifier.size(48.dp).testTag("delete_client_${cli.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete client", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }

    deleteConfirmClient?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmClient = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("DELETE CLIENT?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently remove ${target.companyName.uppercase()} from your workspace clients? Existing projects keep their stored client name.", color = StudioTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteClient(target)
                        deleteConfirmClient = null
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_delete_client_button")
                ) { Text("DELETE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmClient = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }

    editingClient?.let { cli ->
        var companyName by remember { mutableStateOf(cli.companyName) }
        var contactPerson by remember { mutableStateOf(cli.contactPerson) }
        var phone by remember { mutableStateOf(cli.phone) }
        var email by remember { mutableStateOf(cli.email) }
        var address by remember { mutableStateOf(cli.address) }
        var notes by remember { mutableStateOf(cli.meetingNotes) }

        AlertDialog(
            onDismissRequest = { editingClient = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (cli.id.isBlank()) "NEW CLIENT" else "EDIT CLIENT", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = companyName, onValueChange = { companyName = it }, label = { Text("Company Name *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = contactPerson, onValueChange = { contactPerson = it }, label = { Text("Contact Person *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Meeting Notes") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveClient(cli.copy(companyName = companyName, contactPerson = contactPerson, phone = phone, email = email, address = address, meetingNotes = notes))
                        editingClient = null
                    },
                    enabled = companyName.isNotBlank() && contactPerson.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingClient = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

@Composable
fun QuotationsScreen(
    state: AgencyUiState,
    onOpenQuote: (Quotation?) -> Unit,
    onDeleteQuotation: (Quotation) -> Unit = {}
) {
    var statusFilter by remember { mutableStateOf<QuotationStatus?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var deleteConfirmQuote by remember { mutableStateOf<Quotation?>(null) }
    val isSuper = state.isSuperAdmin

    val filtered = remember(state.quotations, statusFilter, searchQuery) {
        state.quotations.filter { q ->
            val stOk = statusFilter == null || q.statusEnum() == statusFilter
            val qOk = searchQuery.isBlank() ||
                q.quoteNumber.contains(searchQuery, ignoreCase = true) ||
                q.projectTitle.contains(searchQuery, ignoreCase = true) ||
                q.clientSnapshot.companyName.contains(searchQuery, ignoreCase = true)
            stOk && qOk
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Quotations (${filtered.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (state.hasPerm(AppPermission.CREATE_QUOTATIONS) || state.hasPerm(AppPermission.ISSUE_QUOTATIONS)) {
                    Button(
                        onClick = { onOpenQuote(null) },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                        modifier = Modifier.height(42.dp)
                    ) { Text("+ NEW QUOTE", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search quote number, client or project…"
            )
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ObsidianCharcoal,
                        selectedLabelColor = PureWhite,
                        containerColor = PureWhite,
                        labelColor = ObsidianCharcoal
                    ),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = statusFilter == null, borderColor = SubtleOutline),
                    label = { Text("All", fontWeight = FontWeight.SemiBold) }
                )
                QuotationStatus.entries.forEach { s ->
                    FilterChip(
                        selected = statusFilter == s,
                        onClick = { statusFilter = s },
                        shape = RoundedCornerShape(999.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ObsidianCharcoal,
                            selectedLabelColor = PureWhite,
                            containerColor = PureWhite,
                            labelColor = ObsidianCharcoal
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = statusFilter == s, borderColor = SubtleOutline),
                        label = { Text(s.label, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyStateCard(Icons.AutoMirrored.Filled.ReceiptLong, "No quotations yet", "Build a quotation from Home or Requests.") }
        } else {
            items(filtered, key = { it.id }) { q ->
                val (bg, fg) = when (q.statusEnum()) {
                    QuotationStatus.CLIENT_APPROVED -> SoftMint to DeepMintInk
                    QuotationStatus.ISSUED, QuotationStatus.SENT -> KpiLavenderBg to VioletPrimary
                    QuotationStatus.SUPERSEDED -> MutedSurface to StudioTextSecondary
                    QuotationStatus.DRAFT -> ButterYellow to DeepAmberInk
                }
                Surface(
                    onClick = { onOpenQuote(q) },
                    shape = RoundedCornerShape(22.dp),
                    color = PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${q.quoteNumber} (v${q.revision})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusPill(q.statusEnum().label, bg, fg)
                                if (isSuper) {
                                    IconButton(
                                        onClick = { deleteConfirmQuote = q },
                                        modifier = Modifier.size(36.dp).testTag("delete_quote_${q.id}")
                                    ) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete quote", tint = CrimsonBlocked, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Text("${q.projectTitle.uppercase()} • ${q.clientSnapshot.companyName.uppercase()}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = StudioTextSecondary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Total: ${MoneyFormatter.formatInr(q.totalPaise)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianCharcoal
                            )
                            Text(
                                "Advance: ${MoneyFormatter.formatInr(q.advancePaise)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    deleteConfirmQuote?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmQuote = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("DELETE QUOTATION?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete quotation ${target.quoteNumber} (v${target.revision}) for ${target.clientSnapshot.companyName.uppercase()}?", color = StudioTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteQuotation(target)
                        deleteConfirmQuote = null
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_delete_quote_button")
                ) { Text("DELETE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmQuote = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

@Composable
fun CatalogueScreen(
    state: AgencyUiState,
    onSaveItem: (CatalogueItem) -> Unit,
    onToggleArchive: (CatalogueItem, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CatalogueItem?>(null) }
    val canEdit = state.hasPerm(AppPermission.EDIT_CATALOGUE_PRICES)

    val filtered = remember(state.catalogue, query, showArchived) {
        state.catalogue.filter {
            (showArchived || !it.archived) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Catalogue (${filtered.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                if (canEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = showArchived,
                            onClick = { showArchived = !showArchived },
                            shape = RoundedCornerShape(999.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ObsidianCharcoal,
                                selectedLabelColor = PureWhite,
                                containerColor = MutedSurface,
                                labelColor = ObsidianCharcoal
                            ),
                            border = null,
                            label = { Text("Archived", fontWeight = FontWeight.SemiBold) }
                        )
                        Button(
                            onClick = { editing = CatalogueItem(sortOrder = state.catalogue.size + 1) },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            modifier = Modifier.height(40.dp)
                        ) { Text("+ Add", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        item {
            MonochromeSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Filter services, packages, or add-ons…"
            )
        }
        itemsIndexed(filtered, key = { _, it -> it.id }) { index, item ->
            CatalogueServiceTicketCard(
                item = item,
                index = index,
                buttonLabel = if (canEdit) "Check" else "Details",
                onButtonClick = {
                    if (canEdit) editing = item
                },
                onCardClick = {
                    if (canEdit) editing = item
                }
            )
        }
    }

    editing?.let { item ->
        var name by remember { mutableStateOf(item.name) }
        var category by remember { mutableStateOf(item.category.ifBlank { "Branding & Identity" }) }
        var desc by remember { mutableStateOf(item.description) }
        var priceRupees by remember { mutableStateOf(MoneyFormatter.paiseToEditableRupees(item.pricePaise)) }

        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text(if (item.id.isBlank()) "Add Catalogue Item" else "Edit ${item.name}", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Service Name *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category *") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(
                        value = priceRupees,
                        onValueChange = { priceRupees = it },
                        label = { Text("Price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val paise = MoneyFormatter.rupeesToPaise(priceRupees) ?: 0L
                        onSaveItem(item.copy(name = name, category = category, description = desc, pricePaise = paise))
                        editing = null
                    },
                    enabled = name.isNotBlank() && category.isNotBlank(),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

@Composable
fun PaymentsScreen(
    state: AgencyUiState,
    onRecordPayment: (Project, Long, Long, PaymentMethod, String, String) -> Unit
) {
    var recordTarget by remember { mutableStateOf<Project?>(null) }
    val activeProjects = state.projects.filter { !it.deleted }
    val totalAgreed = activeProjects.sumOf { it.agreedValuePaise }
    val totalPaid = activeProjects.sumOf { it.paidPaise }
    val totalBalance = activeProjects.sumOf { it.outstandingPaise }
    val canRecord = state.hasPerm(AppPermission.RECORD_PAYMENTS)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Payments & Receivables", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricStatCard("Collected", MoneyFormatter.formatInr(totalPaid), "of ${MoneyFormatter.formatInr(totalAgreed)}", Icons.Filled.AccountBalanceWallet, containerColor = SoftMint, accentColor = DeepMintInk, modifier = Modifier.weight(1f))
                MetricStatCard("Outstanding", MoneyFormatter.formatInr(totalBalance), "${activeProjects.count { it.outstandingPaise > 0L }} open projects", Icons.Filled.Schedule, containerColor = MutedSurface, accentColor = ObsidianCharcoal, modifier = Modifier.weight(1f))
            }
        }
        item { Text("Project Balances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) }
        items(activeProjects, key = { it.id }) { proj ->
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(proj.title.uppercase(), fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal, modifier = Modifier.weight(1f))
                        StatusPill(proj.statusEnum().label, InfoPillBg, InfoPillInk)
                    }
                    Text("${proj.clientCompanyName.uppercase()} • Advance Due: ${MoneyFormatter.formatInr(proj.advanceDuePaise)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                    Text(
                        "Agreed: ${MoneyFormatter.formatInr(proj.agreedValuePaise)} | Paid: ${MoneyFormatter.formatInr(proj.paidPaise)} | Balance: ${MoneyFormatter.formatInr(proj.outstandingPaise)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ObsidianCharcoal
                    )
                    if (canRecord && proj.outstandingPaise > 0L) {
                        Button(
                            onClick = { recordTarget = proj },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite),
                            modifier = Modifier.testTag("record_payment_${proj.id}")
                        ) { Text("RECORD PAYMENT", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        item { Text("Payment Ledger (${state.payments.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) }
        items(state.payments, key = { it.id }) { p ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${MoneyFormatter.formatInr(p.amountPaise)} • ${p.projectTitle.uppercase()}", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                    Text(
                        "${p.clientCompanyName.uppercase()} • ${p.methodEnum().label} • Ref: ${p.reference.ifBlank { "N/A" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                    Text(
                        "By ${p.recordedByName.uppercase()} on ${MoneyFormatter.formatDate(p.paymentDateMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                }
            }
        }
    }

    recordTarget?.let { proj ->
        var amountRupees by remember { mutableStateOf(MoneyFormatter.paiseToEditableRupees(proj.advanceDuePaise.takeIf { proj.paidPaise == 0L && it > 0L } ?: proj.outstandingPaise)) }
        var method by remember { mutableStateOf(PaymentMethod.UPI) }
        var ref by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { recordTarget = null },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Record Payment — ${proj.title}", fontWeight = FontWeight.Bold, color = ObsidianCharcoal) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remaining balance: ${MoneyFormatter.formatInr(proj.outstandingPaise)}", style = MaterialTheme.typography.labelMedium, color = StudioTextSecondary)
                    OutlinedTextField(
                        value = amountRupees,
                        onValueChange = { amountRupees = it },
                        label = { Text("Amount Paid (₹) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaymentMethod.entries.forEach { m ->
                            FilterChip(
                                selected = method == m,
                                onClick = { method = m },
                                shape = RoundedCornerShape(999.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ObsidianCharcoal,
                                    selectedLabelColor = PureWhite,
                                    containerColor = MutedSurface,
                                    labelColor = ObsidianCharcoal
                                ),
                                border = null,
                                label = { Text(m.name, fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                    OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("Transaction Ref / UTR") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                val paise = MoneyFormatter.rupeesToPaise(amountRupees) ?: 0L
                Button(
                    onClick = {
                        onRecordPayment(proj, paise, System.currentTimeMillis(), method, ref, note)
                        recordTarget = null
                    },
                    enabled = paise > 0L,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCharcoal, contentColor = PureWhite)
                ) { Text("Save Payment") }
            },
            dismissButton = { TextButton(onClick = { recordTarget = null }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

@Composable
fun ActivityLogScreen(
    state: AgencyUiState,
    onDeleteLog: (String) -> Unit = {},
    onClearAllLogs: () -> Unit = {}
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(CrispCanvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Internal Activity Log",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ObsidianCharcoal,
                    modifier = Modifier.weight(1f)
                )
                if (state.isSuperAdmin && state.activityLog.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, CrimsonBlocked.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = SoftBlush, contentColor = CrimsonBlocked),
                        modifier = Modifier.testTag("clear_all_logs_button")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CLEAR ALL LOGS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (state.activityLog.isEmpty()) {
            item { EmptyStateCard(Icons.Filled.History, "NO ACTIVITY ENTRIES YET", "Key agency actions appear here automatically.") }
        } else {
            items(state.activityLog, key = { it.id }) { act ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MutedSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp).clip(CircleShape).background(PureWhite),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = ObsidianCharcoal, modifier = Modifier.size(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(act.title.uppercase(), fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal)
                            Text(act.detail, style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
                            Text("By ${act.actorName.uppercase()} • ${act.categoryEnum().label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = StudioTextSecondary)
                        }
                        if (state.isSuperAdmin) {
                            IconButton(
                                onClick = { onDeleteLog(act.id) },
                                modifier = Modifier.size(48.dp).testTag("delete_log_${act.id}")
                            ) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete log entry", tint = CrimsonBlocked)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = PureWhite,
            shape = RoundedCornerShape(26.dp),
            title = { Text("CLEAR ALL ACTIVITY LOGS?", fontWeight = FontWeight.ExtraBold, color = ObsidianCharcoal) },
            text = { Text("Permanently delete all ${state.activityLog.size} activity log entries? This cannot be undone.", color = StudioTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllLogs()
                        showClearConfirm = false
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlocked, contentColor = PureWhite),
                    modifier = Modifier.testTag("confirm_clear_all_logs_button")
                ) { Text("CLEAR LOGS", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel", color = StudioTextSecondary) } }
        )
    }
}

