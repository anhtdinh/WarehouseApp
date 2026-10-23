package com.example.warehouse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

sealed interface Step {
    object Home : Step
    object PickLoc : Step
    data class ScanItem(val loc: String) : Step
    data class Details(val loc: String, val code: String) : Step
}

@Composable
fun App(vm: Vm = viewModel()) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var step by remember { mutableStateOf<Step>(Step.Home) }

    BackHandler(step != Step.Home) {
        step = when (val s = step) {
            is Step.Details -> Step.ScanItem(s.loc)
            else -> Step.Home
        }
    }

    Surface(Modifier.fillMaxSize()) {
        when (val s = step) {
            Step.Home -> HomeScreen(vm) { step = Step.PickLoc }
            else -> if (!granted) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("The camera is needed to scan barcodes and take photos.")
                    Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            } else when (s) {
                Step.PickLoc -> PickLocation({ step = Step.ScanItem(it) }, { step = Step.Home })
                is Step.ScanItem -> ScanItem(s.loc, { step = Step.Details(s.loc, it) }, { step = Step.Home })
                is Step.Details -> Details(s.loc, s.code, vm,
                    onSaved = { step = Step.ScanItem(s.loc) },
                    onCancel = { step = Step.ScanItem(s.loc) })
                else -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: Vm, onScan: () -> Unit) {
    val rows by vm.rows.collectAsState()
    var q by remember { mutableStateOf("") }
    val shown = rows.filter {
        q.isBlank() || it.name.contains(q, true) || it.barcode.contains(q, true) || it.locationCode.contains(q, true)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Warehouse") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onScan) { Text("Scan items") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search item, barcode or location") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                shown.groupBy { it.locationCode }.forEach { (loc, list) ->
                    item(key = "h-$loc") {
                        Text(loc, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    }
                    items(list, key = { "${it.itemId}-$loc" }) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = r.photoPath?.let { File(it) }, contentDescription = r.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp)
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(r.name)
                                Text(r.barcode, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("× ${r.qty}")
                            TextButton({ vm.remove(r.itemId, r.locationCode) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PickLocation(onLoc: (String) -> Unit, onBack: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var fired by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Step 1: scan the shelf / bin label", style = MaterialTheme.typography.titleMedium)
        CameraView(Modifier.fillMaxWidth().weight(1f), onBarcode = {
            if (!fired) { fired = true; onLoc(it.trim().uppercase()) }
        }, capture = null)
        OutlinedTextField(typed, { typed = it }, label = { Text("or type a location, e.g. A-01-1-2") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onLoc(typed.trim().uppercase()) }, enabled = typed.isNotBlank()) { Text("Use this location") }
            TextButton(onBack) { Text("Cancel") }
        }
    }
}

@Composable
fun ScanItem(loc: String, onCode: (String) -> Unit, onDone: () -> Unit) {
    var fired by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Location $loc", style = MaterialTheme.typography.titleMedium)
        Text("Step 2: scan an item barcode")
        CameraView(Modifier.fillMaxWidth().weight(1f), onBarcode = {
            if (!fired) { fired = true; onCode(it) }
        }, capture = null)
        Button(onDone) { Text("Done with this location") }
    }
}

@Composable
fun Details(loc: String, code: String, vm: Vm, onSaved: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var photo by remember { mutableStateOf<String?>(null) }
    var known by remember { mutableStateOf(false) }
    val capture = remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(code) {
        vm.find(code)?.let { name = it.name; photo = it.photoPath; known = true }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("$code  ->  $loc", style = MaterialTheme.typography.titleMedium)
        Text(if (known) "Known item. Quantity will be added to this location." else "New item")
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            val p = photo
            if (p == null) CameraView(Modifier.fillMaxSize(), onBarcode = null, capture = capture)
            else AsyncImage(File(p), "Item photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (photo == null) Button({ takePhoto(ctx, capture.value, code) { photo = it } }) { Text("Take photo") }
        else OutlinedButton({ photo = null }) { Text("Retake photo") }
        OutlinedTextField(name, { name = it }, label = { Text("Item name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text("Quantity to add") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button({
            vm.save(code, name.trim(), photo, loc, qty.toIntOrNull() ?: 1)
            onSaved()
        }, enabled = name.isNotBlank()) { Text("Save to $loc") }
        TextButton(onCancel) { Text("Cancel") }
    }
}

fun takePhoto(ctx: Context, ic: ImageCapture?, code: String, done: (String) -> Unit) {
    if (ic == null) return
    val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "${code.filter { it.isLetterOrDigit() }}-${System.currentTimeMillis()}.jpg")
    ic.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(ctx),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) = done(file.absolutePath)
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(ctx, "Photo failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    )
}
