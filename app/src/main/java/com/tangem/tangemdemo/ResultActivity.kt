package com.tangem.tangemdemo

import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.text.Html
import kotlinx.android.synthetic.main.activity_result.*

class ResultActivity : AppCompatActivity() {
    companion object {
        const val EXTRAS_RESULT_STRING="RESULT_STRING"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvResult.text = Html.fromHtml(intent.extras!!.getString(EXTRAS_RESULT_STRING), Html.FROM_HTML_MODE_COMPACT)
        }else{
            tvResult.text = Html.fromHtml(intent.extras!!.getString(EXTRAS_RESULT_STRING))
        }

        btnReturn.setOnClickListener { finish() }
    }
}
