package org.ergoplatform.android

import StageConstants
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.ergoplatform.android.wallet.WalletStateDbEntity
import org.ergoplatform.api.CoinGeckoApi
import org.ergoplatform.api.ErgoApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


class NodeConnector() {

    val isRefreshing: MutableLiveData<Boolean> = MutableLiveData()
    val refreshNum: MutableLiveData<Int> = MutableLiveData()
    val fiatValue: MutableLiveData<Float> = MutableLiveData()
    val currencies: MutableLiveData<List<String>?> = MutableLiveData()
    var lastRefresMs: Long = 0
        private set
    var lastHadError: Boolean = false
        private set
    var fiatCurrency: String = ""
        private set
    private val ergoApiService: ErgoApi
    private val coingeckoApi: CoinGeckoApi

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(StageConstants.EXPLORER_API_ADDRESS)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        ergoApiService = retrofit.create(ErgoApi::class.java)

        val retrofitCoinGecko = Retrofit.Builder().baseUrl("https://api.coingecko.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        coingeckoApi = retrofitCoinGecko.create(CoinGeckoApi::class.java)
    }

    fun invalidateCache() {
        lastRefresMs = 0
    }

    fun refreshByUser(context: Context): Boolean {
        if (System.currentTimeMillis() - lastRefresMs > 1000L * 10) {
            refreshNow(context)
            return true
        } else
            return false
    }

    fun refreshWhenNeeded(context: Context) {
        if (System.currentTimeMillis() - lastRefresMs > 1000L * 60) {
            refreshNow(context)
        }
    }

    private fun refreshNow(context: Context) {
        if (!(isRefreshing.value ?: false)) {
            isRefreshing.postValue(true)
            GlobalScope.launch(Dispatchers.IO) {
                var hadError = false
                var didSync = false

                // Refresh Ergo fiat value
                fiatCurrency = getPrefDisplayCurrency(context)

                var fFiatValue = fiatValue.value ?: 0f
                if (fiatCurrency.isNotEmpty()) {
                    try {
                        val currencyGetPrice =
                            coingeckoApi.currencyGetPrice(fiatCurrency).execute().body()
                        fFiatValue = currencyGetPrice?.ergoPrice?.get(fiatCurrency) ?: 0f
                    } catch (t: Throwable) {
                        Log.e("CoinGecko", "Error", t)
                        // don't set to zero here, keep last value in case of connection error
                    }
                } else {
                    fFiatValue = 0f
                }
                saveLastFiatValue(context, fFiatValue)
                fiatValue.postValue(fFiatValue)


                // Refresh wallet states
                try {
                    val statesToSave = mutableListOf<WalletStateDbEntity>()
                    val walletDao = AppDatabase.getInstance(context).walletDao()
                    walletDao.getAllSync().forEach { walletConfig ->
                        walletConfig.publicAddress?.let {
                            val transactionsInfo =
                                ergoApiService.addressesIdGet(walletConfig.publicAddress).execute()
                                    .body()?.transactions

                            val newState = WalletStateDbEntity(
                                walletConfig.publicAddress, transactionsInfo?.confirmed,
                                transactionsInfo?.confirmedBalance, transactionsInfo?.totalBalance
                            )

                            statesToSave.add(newState)
                        }
                    }

                    walletDao.insertWalletStates(*statesToSave.toTypedArray())
                    didSync = statesToSave.isNotEmpty()
                } catch (t: Throwable) {
                    Log.e("Nodeconnector", "Error", t)
                    // TODO report to user
                    hadError = true
                }

                if (!hadError && didSync) {
                    lastRefresMs = System.currentTimeMillis()
                    saveLastRefreshMs(context, lastRefresMs)
                }
                lastHadError = hadError
                refreshNum.postValue(refreshNum.value?.and(1) ?: 0)
                isRefreshing.postValue(false)
            }
        }
    }

    fun fetchCurrencies() {
        // do this only once per session, won't change often
        if (currencies.value == null || currencies.value!!.isEmpty()) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    currencies.postValue(null)
                    val currencyList = coingeckoApi.currencies.execute().body()
                    currencies.postValue(currencyList ?: emptyList())
                } catch (t: Throwable) {
                    Log.e("CoinGecko", "Error", t)
                    currencies.postValue(emptyList())
                }
            }
        }
    }

    fun loadPreferenceValues(context: Context) {
        lastRefresMs = getLastRefreshMs(context)
        fiatCurrency = getPrefDisplayCurrency(context)
        fiatValue.postValue(getLastFiatValue(context))
    }

    companion object {

        // For Singleton instantiation
        @Volatile
        private var instance: NodeConnector? = null

        fun getInstance(): NodeConnector {
            return instance ?: synchronized(this) {
                instance ?: NodeConnector().also { instance = it }
            }
        }

    }
}