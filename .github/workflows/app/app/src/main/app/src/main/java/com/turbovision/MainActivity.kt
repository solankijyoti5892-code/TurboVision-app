package com.turbovision

import android.os.Bundle
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val isIncome: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Insert
    suspend fun insert(transaction: Transaction)
}

@Database(entities = [Transaction::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "turbovision_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val dao = db.transactionDao()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TurboVisionMainScreen(dao)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurboVisionMainScreen(dao: TransactionDao) {
    val coroutineScope = rememberCoroutineScope()
    val transactions by dao.getAllTransactions().collectAsState(initial = emptyList())

    val totalIncome = transactions.filter { it.isIncome }.sumOf { it.amount }
    val totalExpense = transactions.filter { !it.isIncome }.sumOf { it.amount }
    val currentBalance = totalIncome - totalExpense

    var showAddDialog by remember { mutableStateOf(false) }
    var inputTitle by remember { mutableStateOf("") }
    var inputAmount by remember { mutableStateOf("") }
    var isIncomeType by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
        if (!spokenText.isNullOrEmpty()) {
            val amount = spokenText.replace(Regex("[^0-9]"), "").toDoubleOrNull() ?: 0.0
            val isInc = spokenText.contains("મળ્યા") || spokenText.contains("આવક")
            if (amount > 0) {
                coroutineScope.launch {
                    dao.insert(Transaction(title = spokenText, amount = amount, isIncome = isInc))
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TurboVision (ટર્બોવિઝન)") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "gu-IN")
                }
                speechLauncher.launch(intent)
            }) { Text("🎤") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("કુલ સિલક (Current Balance)", style = MaterialTheme.typography.titleMedium)
                    Text("₹ $currentBalance", style = MaterialTheme.typography.headlineLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("આવક: ₹ $totalIncome", color = Color(0xFF2E7D32))
                        Text("ખર્ચ: ₹ $totalExpense", color = Color(0xFFC62828))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { isIncomeType = true; showAddDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("➕ આવક")
                }
                Button(onClick = { isIncomeType = false; showAddDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("➖ ખર્ચ")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("રોજમેર (Ledger)", style = MaterialTheme.typography.titleMedium)

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(transactions) { tx ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tx.title)
                            Text((if (tx.isIncome) "+ ₹" else "- ₹") + tx.amount, color = if (tx.isIncome) Color(0xFF2E7D32) else Color(0xFFC62828))
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text(if (isIncomeType) "આવક ઉમેરો" else "ખર્ચ ઉમેરો") },
                text = {
                    Column {
                        OutlinedTextField(value = inputTitle, onValueChange = { inputTitle = it }, label = { Text("વિગત") })
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = inputAmount, onValueChange = { inputAmount = it }, label = { Text("રકમ (₹)") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val amt = inputAmount.toDoubleOrNull() ?: 0.0
                        if (inputTitle.isNotBlank() && amt > 0) {
                            coroutineScope.launch { dao.insert(Transaction(title = inputTitle, amount = amt, isIncome = isIncomeType)) }
                            inputTitle = ""; inputAmount = ""; showAddDialog = false
                        }
                    }) { Text("સાચવો") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("રદ કરો") } }
            )
        }
    }
}
