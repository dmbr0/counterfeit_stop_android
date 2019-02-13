package com.tangem.tangemdemo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tangem.tangemcard.android.data.Firmwares
import com.tangem.tangemcard.android.data.PINStorage
import com.tangem.tangemcard.android.nfc.NfcDeviceAntennaLocation
import com.tangem.tangemcard.android.reader.NfcManager
import com.tangem.tangemcard.android.reader.NfcReader
import com.tangem.tangemcard.data.Issuer
import com.tangem.tangemcard.data.TangemCard
import com.tangem.tangemcard.data.external.FirmwaresDigestsProvider
import com.tangem.tangemcard.data.external.PINsProvider
import com.tangem.tangemcard.reader.CardProtocol
import com.tangem.tangemcard.reader.SettingsMask
import com.tangem.tangemcard.reader.TLV
import com.tangem.tangemcard.tasks.ReadCardInfoTask
import com.tangem.tangemcard.util.Util
import com.tangem.tangemserver.android.data.LocalStorage
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback, CardProtocol.Notifications {

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
    }

    private lateinit var nfcManager: NfcManager
    private lateinit var nfcDeviceAntenna: NfcDeviceAntennaLocation
    private var unsuccessfulReadCount = 0
    private var lastTag: Tag? = null
    private var readCardInfoTask: ReadCardInfoTask? = null
    private var onNfcReaderCallback: NfcAdapter.ReaderCallback? = null

    private lateinit var pinStorage: PINsProvider
    private lateinit var firmwaresStorage: FirmwaresDigestsProvider
    private lateinit var localStorage: LocalStorage

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action || NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action)) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null && onNfcReaderCallback != null)
                onNfcReaderCallback!!.onTagDiscovered(tag)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PINStorage.init(applicationContext)
        pinStorage = PINStorage()
        firmwaresStorage = Firmwares(applicationContext)
        localStorage = LocalStorage(applicationContext)

        val issuers = ArrayList<Issuer>()

        val listType = object : TypeToken<List<Issuer>>() {}.type

        val jsonIssuers: String = "[\n" +
                "  {\n" +
                "    \"id\": \"TANGEM SDK\",\n" +
                "    \"dataKey\": {\n" +
                "      \"privateKey\": \"11121314151617184771ED81F2BACF57479E4735EB1405083927372D40DA9E92\"\n" +
                "    },\n" +
                "    \"transactionKey\": {\n" +
                "      \"privateKey\": \"11121314151617184771ED81F2BACF57479E4735EB1405081918171615141312\"\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": \"TANGEM\",\n" +
                "    \"dataKey\": {\n" +
                "      \"publicKey\": \"048196AA4B410AC44A3B9CCE18E7BE226AEA070ACC83A9CF67540FAC49AF25129F6A538A28AD6341358E3C4F9963064F7E365372A651D374E5C23CDD37FD099BF2\"\n" +
                "    },\n" +
                "    \"transactionKey\": {\n" +
                "      \"publicKey\": \"04343D40496CBE1FE8A8C026575C435A29141EA3BC335DA5549AB6C64685A646848036D481CF9A989390A8B034B229D99BD49E6F07D2FF02746EA265EF99380A80\"\n" +
                "    }\n" +
                "  }\n" +
                "]\n"

        Issuer.fillIssuers(Gson().fromJson(jsonIssuers, listType))

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        nfcManager = NfcManager(this, this)

        verifyPermissions()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

        setNfcAdapterReaderCallback(this)

        rippleBackgroundNfc.startRippleAnimation()

        // init NFC Antenna
        nfcDeviceAntenna = NfcDeviceAntennaLocation(this, ivHandCardHorizontal, ivHandCardVertical, llHand, llNfc)
        nfcDeviceAntenna.init()

        // set phone name
        if (nfcDeviceAntenna.fullName != "")
            tvNFCHint.text = String.format(getString(R.string.scan_banknote), nfcDeviceAntenna.fullName)
        else
            tvNFCHint.text = String.format(getString(R.string.scan_banknote), getString(R.string.phone))

        // NFC
        val intent = intent
        if (intent != null && (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action || NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action)) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null && onNfcReaderCallback != null) {
                onNfcReaderCallback!!.onTagDiscovered(tag)
            }
        }
    }

    private fun verifyPermissions() {
        NfcManager.verifyPermissions(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        try {
            val isoDep = IsoDep.get(tag)
                ?: throw CardProtocol.TangemException(getString(R.string.wrong_tag_err))

            if (unsuccessfulReadCount < 2) {
                isoDep.timeout = 2000 + 5000 * unsuccessfulReadCount
            } else {
                isoDep.timeout = 90000
            }
            lastTag = tag

            readCardInfoTask = ReadCardInfoTask(NfcReader(nfcManager, isoDep), localStorage, pinStorage, this)
            readCardInfoTask!!.start()

        } catch (e: Exception) {
            e.printStackTrace()
            nfcManager.notifyReadResult(false)
        }
    }

    public override fun onResume() {
        super.onResume()
        nfcDeviceAntenna.animate()
        ReadCardInfoTask.resetLastReadInfo()
        nfcManager.onResume()
    }

    public override fun onPause() {
        nfcManager.onPause()
        if (readCardInfoTask != null) {
            readCardInfoTask!!.cancel(true)
        }
        super.onPause()
    }

    public override fun onStop() {
        nfcManager.onStop()
        if (readCardInfoTask != null) {
            readCardInfoTask!!.cancel(true)
        }
        super.onStop()
    }

    override fun onReadStart(cardProtocol: CardProtocol) {
        rlProgressBar.post { rlProgressBar.visibility = View.VISIBLE }
    }

    override fun onReadProgress(protocol: CardProtocol, progress: Int) {
    }

    override fun onReadFinish(cardProtocol: CardProtocol?) {
        readCardInfoTask = null
        if (cardProtocol != null) {
            if (cardProtocol.error == null) {
                nfcManager.notifyReadResult(true)
                rlProgressBar.post {
                    rlProgressBar.visibility = View.GONE

                    val intent = Intent(this, ResultActivity::class.java)

                    val resultString = StringBuilder()
                    val card = cardProtocol.card
                    resultString.append("Card: <b>" + card.cidDescription + "</b><br>")
                    resultString.append("Status: <b>" + card.status + "</b><br>")
                    resultString.append("Firmware: <b>" + card.firmwareVersion + "</b><br>")
                    resultString.append("Manufacturer: <b>" + card.manufacturer.officialName + "</b><br>")
                    resultString.append("Health: <b>" + card.health.toString() + "</b><br>")
                    if (card.status != TangemCard.Status.Empty || card.status != TangemCard.Status.NotPersonalized) {
                        resultString.append("Card public key: <b>" + Util.bytesToHex(card.cardPublicKey) + "</b><br>")
                        resultString.append("Issuer: <b>" + card.issuerDescription + "</b><br>")
                        resultString.append("Settings: <b>" + SettingsMask.getDescription(card.settingsMask) + "</b><br>")
                        resultString.append("Curve: <b>" + cardProtocol.readResult.getTLV(TLV.Tag.TAG_CurveID).asString + "</b><br>")
                        resultString.append("Blockchain: <b>" + card.blockchainID + "</b><br>")
                        if (!card.tokenSymbol.isNullOrEmpty()) {
                            resultString.append("&nbsp;Token: <b>" + card.tokenSymbol + "</b><br>")
                            resultString.append("&nbsp;Contract: <b>" + card.contractAddress + "</b><br>")
                            resultString.append("&nbsp;Token decimal: <b>" + card.tokensDecimal.toString() + "</b><br>")
                        }
                    }
                    if (card.status == TangemCard.Status.Loaded) {
                        resultString.append("Wallet public key: <b>" + Util.bytesToHex(card.walletPublicKey) + "</b><br>")
                    }
                    //resultString.append("Read data: <br>"+cardProtocol.readResult.getParsedTLVs(""))
                    intent.putExtra(ResultActivity.EXTRAS_RESULT_STRING, resultString.toString())
                    startActivity(intent)
                }
            } else {
                rlProgressBar.post {
                    Toast.makeText(this, R.string.try_to_scan_again, Toast.LENGTH_SHORT).show()
                    unsuccessfulReadCount++

                    if (cardProtocol.error is CardProtocol.TangemException_InvalidPIN)
                        doEnterPIN()
                    else {
                        if (cardProtocol.error is CardProtocol.TangemException_ExtendedLengthNotSupported)
                            if (!NoExtendedLengthSupportDialog.allReadyShowed)
                                NoExtendedLengthSupportDialog().show(
                                    supportFragmentManager,
                                    NoExtendedLengthSupportDialog.TAG
                                )

                        lastTag = null
                        ReadCardInfoTask.resetLastReadInfo()
                        nfcManager.notifyReadResult(false)
                    }
                }
            }
        }

        rlProgressBar.postDelayed({
            try {
                rlProgressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 500)
    }

    private fun doEnterPIN() {
        Toast.makeText(
            this,
            "Card protected by PIN. This demo don't support reading of cards protected by PIN",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onReadCancel() {
        readCardInfoTask = null
        ReadCardInfoTask.resetLastReadInfo()
        rlProgressBar.postDelayed({
            try {
                rlProgressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 500)
    }

    override fun onReadWait(msec: Int) {
        WaitSecurityDelayDialog.OnReadWait(Objects.requireNonNull(this), msec)
    }

    override fun onReadBeforeRequest(timeout: Int) {
        WaitSecurityDelayDialog.onReadBeforeRequest(Objects.requireNonNull(this), timeout)
    }

    override fun onReadAfterRequest() {
        WaitSecurityDelayDialog.onReadAfterRequest(Objects.requireNonNull(this))
    }

    private fun setNfcAdapterReaderCallback(callback: NfcAdapter.ReaderCallback) {
        onNfcReaderCallback = callback
    }

}