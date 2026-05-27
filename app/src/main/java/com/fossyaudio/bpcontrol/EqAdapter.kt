package com.fossyaudio.bpcontrol

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fossyaudio.bpcontrol.shared.model.FilterBand
import java.util.Locale

class EqAdapter(
    private val bands: List<FilterBand>,
    private val onUpdate: (Int, FilterBand) -> Unit
) : RecyclerView.Adapter<EqAdapter.ViewHolder>() {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.bandEnable)
        val type: AutoCompleteTextView = v.findViewById(R.id.typeSelector)
        val freq: EditText = v.findViewById(R.id.editFreq)
        val gain: EditText = v.findViewById(R.id.editGain)
        val q: EditText = v.findViewById(R.id.editQ)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_eq_band, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val band = bands[position]

        // Clear listeners before updating UI so recycled views do not trigger update spam.
        holder.check.setOnCheckedChangeListener(null)
        holder.freq.setOnEditorActionListener(null)
        holder.gain.setOnEditorActionListener(null)
        holder.q.setOnEditorActionListener(null)

        holder.check.isChecked = band.enabled
        holder.freq.setText(band.freq.toString())
        holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
        holder.q.setText(String.format(Locale.US, "%.2f", band.q))

        val types = arrayOf("PK", "LS", "HS")
        val typeAdapter = ArrayAdapter(holder.itemView.context, android.R.layout.simple_list_item_1, types)
        holder.type.setAdapter(typeAdapter)
        holder.type.inputType = InputType.TYPE_NULL
        holder.type.setText(band.type, false)

        holder.type.setOnItemClickListener { _, _, pos, _ ->
            band.type = types[pos]
            onUpdate(position, band)
        }

        holder.check.setOnCheckedChangeListener { _, isChecked ->
            band.enabled = isChecked
            onUpdate(position, band)
        }

        val updateAction = TextView.OnEditorActionListener { v, _, _ ->
            try {
                val fInput = holder.freq.text.toString().toIntOrNull()
                if (fInput != null && fInput in 20..20000) {
                    band.freq = fInput
                } else {
                    holder.freq.setText(band.freq.toString())
                }

                val gInput = holder.gain.text.toString().toFloatOrNull()
                if (gInput != null && gInput in -10f..10f) {
                    band.gain = Math.round(gInput * 100) / 100f
                    holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                } else {
                    holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                }

                val qInput = holder.q.text.toString().toFloatOrNull()
                if (qInput != null && qInput in 0.1f..10f) {
                    band.q = Math.round(qInput * 100) / 100f
                    holder.q.setText(String.format(Locale.US, "%.2f", band.q))
                } else {
                    holder.q.setText(String.format(Locale.US, "%.2f", band.q))
                }

                onUpdate(position, band)
            } catch (_: Exception) {
                holder.freq.setText(band.freq.toString())
                holder.gain.setText(String.format(Locale.US, "%.2f", band.gain))
                holder.q.setText(String.format(Locale.US, "%.2f", band.q))
            }
            v.clearFocus()
            false
        }

        holder.freq.setOnEditorActionListener(updateAction)
        holder.gain.setOnEditorActionListener(updateAction)
        holder.q.setOnEditorActionListener(updateAction)
    }

    override fun getItemCount() = bands.size
}
