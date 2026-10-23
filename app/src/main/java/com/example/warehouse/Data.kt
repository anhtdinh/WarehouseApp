package com.example.warehouse

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// One row per product (identified by its barcode)
@Entity(tableName = "items", indices = [Index(value = ["barcode"], unique = true)])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val name: String,
    val photoPath: String?
)

// How many of an item sit in a location (one item can be in many locations)
@Entity(tableName = "stock", primaryKeys = ["itemId", "locationCode"])
data class Stock(val itemId: Long, val locationCode: String, val qty: Int)

data class StockRow(
    val itemId: Long, val barcode: String, val name: String,
    val photoPath: String?, val locationCode: String, val qty: Int
)

@Dao
interface WhDao {
    @Query("SELECT * FROM items WHERE barcode = :b LIMIT 1")
    suspend fun itemByBarcode(b: String): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(i: Item): Long

    @Query("SELECT qty FROM stock WHERE itemId = :i AND locationCode = :l")
    suspend fun qty(i: Long, l: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(s: Stock)

    @Query("DELETE FROM stock WHERE itemId = :i AND locationCode = :l")
    suspend fun deleteStock(i: Long, l: String)

    @Query(
        """SELECT i.id AS itemId, i.barcode AS barcode, i.name AS name,
                  i.photoPath AS photoPath, s.locationCode AS locationCode, s.qty AS qty
           FROM stock s JOIN items i ON i.id = s.itemId
           ORDER BY s.locationCode, i.name"""
    )
    fun rows(): Flow<List<StockRow>>
}

@Database(entities = [Item::class, Stock::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): WhDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "warehouse.db")
                .build().also { inst = it }
        }
    }
}

class Vm(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()

    val rows = dao.rows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun find(barcode: String): Item? = dao.itemByBarcode(barcode)

    /** Adds [add] units of the item to [loc]; creates the item if it is new. */
    fun save(barcode: String, name: String, photo: String?, loc: String, add: Int) {
        viewModelScope.launch {
            val existing = dao.itemByBarcode(barcode)
            val id = if (existing == null) {
                dao.upsertItem(Item(0, barcode, name, photo))
            } else {
                dao.upsertItem(existing.copy(name = name, photoPath = photo ?: existing.photoPath))
                existing.id
            }
            dao.upsertStock(Stock(id, loc, (dao.qty(id, loc) ?: 0) + add))
        }
    }

    fun remove(itemId: Long, loc: String) {
        viewModelScope.launch { dao.deleteStock(itemId, loc) }
    }
}
