package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

class CashBalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (appWidgetIds.isEmpty()) return

        // 1. Immediately render cached value to avoid blank state on load / after force-close
        try {
            val cachedBalance = getCachedCashBalance(context)
            renderWidgets(context, appWidgetManager, appWidgetIds, cachedBalance)
        } catch (e: Exception) {
            Log.e("CashBalanceWidget", "cached render failed", e)
        }

        // 2. Fetch fresh database balance asynchronously
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgets(context, appWidgetManager, appWidgetIds)
            } catch (e: Exception) {
                Log.e("CashBalanceWidget", "update failed", e)
            } finally {
                try {
                    pendingResult?.finish()
                } catch (e: Exception) {
                    Log.e("CashBalanceWidget", "finish pending result failed", e)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        
        // Immediately render cached value for the changed option
        try {
            val cachedBalance = getCachedCashBalance(context)
            renderWidgets(context, appWidgetManager, intArrayOf(appWidgetId), cachedBalance)
        } catch (e: Exception) {
            Log.e("CashBalanceWidget", "cached render failed", e)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
            } catch (e: Exception) {
                Log.e("CashBalanceWidget", "update failed", e)
            } finally {
                try {
                    pendingResult?.finish()
                } catch (e: Exception) {
                    Log.e("CashBalanceWidget", "finish pending result failed", e)
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGETS) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisWidget = ComponentName(context, CashBalanceWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget) ?: return
            if (allWidgetIds.isNotEmpty()) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        updateWidgets(context, appWidgetManager, allWidgetIds)
                    } catch (e: Exception) {
                        Log.e("CashBalanceWidget", "update failed", e)
                    } finally {
                        try {
                            pendingResult?.finish()
                        } catch (e: Exception) {
                            Log.e("CashBalanceWidget", "finish pending result failed", e)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGETS = "com.example.widget.ACTION_UPDATE_CASH_BALANCE"
        private const val PREFS_NAME = "taka_tracker_prefs"
        private const val PREF_KEY_CACHED_BALANCE = "cached_widget_cash_balance"

        fun updateAllWidgets(context: Context) {
            try {
                val intent = Intent(context, CashBalanceWidgetProvider::class.java).apply {
                    action = ACTION_UPDATE_WIDGETS
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e("CashBalanceWidget", "update failed", e)
            }
        }

        fun getCachedCashBalance(context: Context): String {
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currencySymbol = sharedPrefs?.getString("currency_symbol", "৳") ?: "৳"
            return sharedPrefs?.getString(PREF_KEY_CACHED_BALANCE, null) ?: "${currencySymbol}0"
        }

        fun setCachedCashBalance(context: Context, balance: String) {
            try {
                val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sharedPrefs?.edit()?.putString(PREF_KEY_CACHED_BALANCE, balance)?.apply()
            } catch (e: Exception) {
                Log.e("CashBalanceWidget", "cache write failed", e)
            }
        }

        fun renderWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            formattedBalance: String
        ) {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val addTxIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_ADD_TRANSACTION
                putExtra(MainActivity.EXTRA_OPEN_ADD_TRANSACTION, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val addTxPendingIntent = PendingIntent.getActivity(
                context,
                1001,
                addTxIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (appWidgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                val is2x2 = minHeight >= 75

                val views = if (is2x2) {
                    try {
                        RemoteViews(context.packageName, R.layout.widget_cash_balance_2x2).apply {
                            setTextViewText(R.id.widget_cash_balance, formattedBalance)
                            setContentDescription(R.id.widget_cash_balance, "Cash balance: $formattedBalance")
                            setContentDescription(R.id.widget_btn_add, context.getString(R.string.widget_add_transaction))
                            setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                            setOnClickPendingIntent(R.id.widget_btn_add, addTxPendingIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("CashBalanceWidget", "2x2 layout failed specifically", e)
                        RemoteViews(context.packageName, R.layout.widget_cash_balance_2x1).apply {
                            setTextViewText(R.id.widget_cash_balance, formattedBalance)
                            setContentDescription(R.id.widget_cash_balance, "Cash balance: $formattedBalance")
                            setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                        }
                    }
                } else {
                    RemoteViews(context.packageName, R.layout.widget_cash_balance_2x1).apply {
                        setTextViewText(R.id.widget_cash_balance, formattedBalance)
                        setContentDescription(R.id.widget_cash_balance, "Cash balance: $formattedBalance")
                        setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                    }
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private suspend fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val formattedBalance = getFormattedCashBalance(context)
            setCachedCashBalance(context, formattedBalance)
            renderWidgets(context, appWidgetManager, appWidgetIds, formattedBalance)
        }

        private suspend fun getFormattedCashBalance(context: Context): String {
            return try {
                val sharedPrefs = context.getSharedPreferences("taka_tracker_prefs", Context.MODE_PRIVATE)
                val currencySymbol = sharedPrefs?.getString("currency_symbol", "৳") ?: "৳"

                val database = DatabaseProvider.getDatabase(context.applicationContext)
                val allTransactions = database.dao.getAllTransactions()

                val calendar = Calendar.getInstance()
                val targetMonth = calendar.get(Calendar.MONTH)
                val targetYear = calendar.get(Calendar.YEAR)

                val monthlyTransactions = allTransactions.filter { tx ->
                    val txCal = Calendar.getInstance().apply { timeInMillis = tx.date }
                    txCal.get(Calendar.MONTH) == targetMonth && txCal.get(Calendar.YEAR) == targetYear
                }

                var totalEarnings = 0.0
                var totalExpenses = 0.0
                var totalSavingsContributed = 0.0

                for (tx in monthlyTransactions) {
                    if (tx.type == "INCOME") {
                        totalEarnings += tx.amount
                    } else if (tx.type == "EXPENSE") {
                        if (tx.categoryName == "Savings" || tx.categoryName == "Goal Savings") {
                            totalSavingsContributed += tx.amount
                        } else {
                            totalExpenses += tx.amount
                        }
                    }
                }

                val cashBalance = totalEarnings - totalExpenses - totalSavingsContributed

                val formatter = NumberFormat.getNumberInstance(Locale.US)
                "$currencySymbol${formatter.format(cashBalance)}"
            } catch (e: Exception) {
                Log.e("CashBalanceWidget", "update failed", e)
                "৳0"
            }
        }
    }
}
