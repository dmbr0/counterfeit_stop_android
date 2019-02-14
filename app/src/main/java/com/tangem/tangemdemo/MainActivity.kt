package com.tangem.tangemdemo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

/***
 *
 * Simple example how to use tangemcard libraries to read Tangem cards on android phone with NFC.
 *
 * This example show:
 * 1. Setup permissions to access NFC reader (in AndroidManifest.xml and check&request permissions on MainActivity)
 * 2. Setup intent filters in AndroidManifest.xml to startup application by simple tap on Tangem card
 * 3. Setup local environment (PINStorage, FirmwareStorage, Issuers and etc) to use tangemcard libraries
 * 4. Setup scan card activity layout (MainActivity) to show user how to tap card according to the phone's NFC reader location
 * 5. Setup NFC and tangem card callbacks to run ReadCardInfoTask and process read result
 * 6. How to workaround with security delay (show WaitSecurityDelayDialog), enforced by card
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback, CardProtocol.Notifications {

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
    }

    // NFC helper
    private lateinit var nfcManager: NfcManager

    // widget to show NFC reader location
    private lateinit var nfcDeviceAntenna: NfcDeviceAntennaLocation

    // this counter used to increase timeout if card read fail by some reason
    private var unsuccessfulReadCount = 0

    // task that run when tap card
    private var readCardInfoTask: ReadCardInfoTask? = null

    // local data storages used by tangemcard libraries

    // pin's list, that can be used to read card
    private lateinit var pinStorage: PINsProvider
    // card firmwares hashes (to prove card authenticity)
    private lateinit var firmwaresStorage: FirmwaresDigestsProvider
    // card artwork's and substitution data
    private lateinit var localStorage: LocalStorage

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // start reading card if NFC intent received
        if (intent != null && (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action || NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action)) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null )
                onTagDiscovered(tag)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // initialize local storage
        PINStorage.init(applicationContext)
        pinStorage = PINStorage()
        firmwaresStorage = Firmwares(applicationContext)
        localStorage = LocalStorage(applicationContext)

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

        // create NFC helper and setup activity as NFC callback receiver
        nfcManager = NfcManager(this, this)

        // verify NFC permissions
        verifyPermissions()

        rippleBackgroundNfc.startRippleAnimation()

        // create widget wit NFC reader location
        nfcDeviceAntenna = NfcDeviceAntennaLocation(this, ivHandCardHorizontal, ivHandCardVertical, llHand, llNfc)
        nfcDeviceAntenna.init()

        // set phone name
        if (nfcDeviceAntenna.fullName != "")
            tvNFCHint.text = String.format(getString(R.string.scan_banknote), nfcDeviceAntenna.fullName)
        else
            tvNFCHint.text = String.format(getString(R.string.scan_banknote), getString(R.string.phone))

        // immediately start reading if activity start with NFC intent
        val intent = intent
        if (intent != null && (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action || NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action)) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null ) {
                onTagDiscovered(tag)
            }
        }
    }

    private fun verifyPermissions() {
        NfcManager.verifyPermissions(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        // this function call when user tap card
        try {
            val isoDep = IsoDep.get(tag)
                ?: throw CardProtocol.TangemException(getString(R.string.wrong_tag_err))

            if (unsuccessfulReadCount < 2) {
                isoDep.timeout = 2000 + 5000 * unsuccessfulReadCount
            } else {
                isoDep.timeout = 90000
            }
            // When first time read the card ReadCardInfoTask must be executed to read card information
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
        // start NFC reader when activity resume
        ReadCardInfoTask.resetLastReadInfo()
        nfcManager.onResume()
    }

    public override fun onPause() {
        // when activity pause break readCardInfoTask if it executed and stop NFC reading
        nfcManager.onPause()
        if (readCardInfoTask != null) {
            readCardInfoTask!!.cancel(true)
        }
        super.onPause()
    }

    public override fun onStop() {
        // when activity stop break readCardInfoTask if it executed and stop NFC reading
        nfcManager.onStop()
        if (readCardInfoTask != null) {
            readCardInfoTask!!.cancel(true)
        }
        super.onStop()
    }

    override fun onReadStart(cardProtocol: CardProtocol) {
        // this function call before start reading task
        // this function call in separate reading thread therefore using post to access UI

        // show reading progress bar
        rlProgressBar.post { rlProgressBar.visibility = View.VISIBLE }
    }

    override fun onReadProgress(protocol: CardProtocol, progress: Int) {
        // this function may call during reading task execution to notify progress
        // this function call in separate reading thread therefore using post to access UI
    }

    override fun onReadFinish(cardProtocol: CardProtocol?) {
        // this function call when reading task is finished
        // this function call in separate reading thread therefore using post to access UI
        readCardInfoTask = null
        if (cardProtocol != null) {
            if (cardProtocol.error == null) {
                // reading task finished successfully
                // in this place possible run next task (sign for example) or switch to new activity
                nfcManager.notifyReadResult(true)
                rlProgressBar.post {
                    rlProgressBar.visibility = View.GONE

                    // for this simple example just show activity with card content description
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
                    // uncomment next line to view all read result tlv description
                    // resultString.append("Read data: <br>"+cardProtocol.readResult.getParsedTLVs(""))
                    intent.putExtra(ResultActivity.EXTRAS_RESULT_STRING, resultString.toString())
                    startActivity(intent)
                }
            } else {
                // card reading finish with some error
                rlProgressBar.post {
                    Toast.makeText(this, R.string.try_to_scan_again, Toast.LENGTH_SHORT).show()
                    unsuccessfulReadCount++

                    if (cardProtocol.error is CardProtocol.TangemException_InvalidPIN)
                        // possible card is protected by PIN, user must enter PIN and try again
                        doEnterPIN()
                    else {
                        if (cardProtocol.error is CardProtocol.TangemException_ExtendedLengthNotSupported) {
                            // phone's NFC stack don't support APDU with extended length, card can't be read on this device
                            if (!NoExtendedLengthSupportDialog.allReadyShowed)
                                NoExtendedLengthSupportDialog().show(
                                    supportFragmentManager,
                                    NoExtendedLengthSupportDialog.TAG
                                )
                        }

                        ReadCardInfoTask.resetLastReadInfo()
                        nfcManager.notifyReadResult(false)
                    }
                }
            }
        }

        // hide progress bar (with some delay for better UI usability)
        rlProgressBar.postDelayed({
            try {
                rlProgressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 500)
    }

    private fun doEnterPIN() {
        // workaround with request PIN
        // not supported in this example
        Toast.makeText(
            this,
            "Card protected by PIN. This demo don't support reading of cards protected by PIN",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onReadCancel() {
        // this function call when reading task canceled by some reason (activity goes to background for example)
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
        // this function call when card enforce security delay and repeatedly call approximately every second during security delay
        WaitSecurityDelayDialog.OnReadWait(Objects.requireNonNull(this), msec)
    }

    override fun onReadBeforeRequest(timeout: Int) {
        // this function call before every APDU command
        // for compatibility with old card firmwares (that not support security delay notifications)
        // show WaitSecurityDelayDialog after some small time to notify user
        WaitSecurityDelayDialog.onReadBeforeRequest(Objects.requireNonNull(this), timeout)
    }

    override fun onReadAfterRequest() {
        // this function call after every APDU command
        // for compatibility with old card firmwares (that not support security delay notifications)
        // hide WaitSecurityDelayDialog if it was showed
        WaitSecurityDelayDialog.onReadAfterRequest(Objects.requireNonNull(this))
    }

}