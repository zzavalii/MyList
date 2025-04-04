package softserve.academy.mylist

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.*
import androidx.room.*
import com.google.accompanist.swiperefresh.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Entity(tableName = "shopping_items")
data class ShoppingItem(
    val name: String,
    val isBought: Boolean = false,
    @PrimaryKey(autoGenerate = true) val id: Int = 0
)

@Dao
interface ShoppingDao {
    @Query("SELECT * FROM shopping_items ORDER BY id DESC")
    suspend fun getAllItems(): List<ShoppingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ShoppingItem)

    @Update
    suspend fun updateItem(item: ShoppingItem)

    @Delete
    suspend fun deleteItem(item: ShoppingItem)
}

@Database(entities = [ShoppingItem::class], version = 1)
abstract class ShoppingDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao

    companion object {
        @Volatile
        private var INSTANCE: ShoppingDatabase? = null

        fun getInstance(context: android.content.Context): ShoppingDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ShoppingDatabase::class.java,
                    "shopping_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class ShoppingListViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ShoppingDatabase.getInstance(application).shoppingDao()
    private val _shoppingList = mutableStateListOf<ShoppingItem>()
    val shoppingList: List<ShoppingItem> get() = _shoppingList

    var isRefreshing by mutableStateOf(false)
        private set

    init {
        refreshList()
    }

    fun refreshList() {
        viewModelScope.launch {
            isRefreshing = true
            val items = withContext(Dispatchers.IO) { dao.getAllItems() }
            _shoppingList.clear()
            _shoppingList.addAll(items)
            isRefreshing = false
        }
    }

    fun addItem(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertItem(ShoppingItem(name = name))
            refreshList()
        }
    }

    fun toggleBought(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = _shoppingList[index]
            val updated = item.copy(isBought = !item.isBought)
            dao.updateItem(updated)
            withContext(Dispatchers.Main) {
                _shoppingList[index] = updated
            }
        }
    }

    fun deleteItem(item: ShoppingItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteItem(item)
            refreshList()
        }
    }

    fun updateItem(item: ShoppingItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateItem(item)
            refreshList()
        }
    }

    fun getBoughtCount(): Int = shoppingList.count { it.isBought }
}

class ShoppingListViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ShoppingListViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ShoppingListViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current.applicationContext as Application
            val viewModel: ShoppingListViewModel = viewModel(
                factory = ShoppingListViewModelFactory(context)
            )
            MyListApp(viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListApp(viewModel: ShoppingListViewModel) {
    val state = SwipeRefreshState(viewModel.isRefreshing)

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("Список покупок (${viewModel.getBoughtCount()} куплено)")
            })
        }
    ) { padding ->
        SwipeRefresh(
            state = state,
            onRefresh = { viewModel.refreshList() },
            modifier = Modifier.padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                AddItemField { viewModel.addItem(it) }
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    itemsIndexed(viewModel.shoppingList) { index, item ->
                        ShoppingItemCard(
                            item,
                            onToggleBought = { viewModel.toggleBought(index) },
                            onDelete = { viewModel.deleteItem(item) },
                            onEdit = { newName ->
                                viewModel.updateItem(item.copy(name = newName))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddItemField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Новий товар") },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            if (text.isNotBlank()) {
                onAdd(text)
                text = ""
            }
        }) {
            Text("Додати")
        }
    }
}

@Composable
fun ShoppingItemCard(
    item: ShoppingItem,
    onToggleBought: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(TextFieldValue(item.name)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .background(if (item.isBought) Color(0xFFD0F0C0) else Color.White),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.isBought,
                onCheckedChange = { onToggleBought() }
            )
            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    isEditing = false
                    if (editText.text.isNotBlank()) onEdit(editText.text)
                }) {
                    Icon(Icons.Default.Edit, contentDescription = "Зберегти")
                }
            } else {
                Text(item.name, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { isEditing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Редагувати")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Видалити")
            }
        }
    }
}
