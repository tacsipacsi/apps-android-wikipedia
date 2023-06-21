package org.wikipedia.views

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.wikipedia.R
import org.wikipedia.database.AppDatabase
import org.wikipedia.databinding.DialogWikiWrappedBinding
import org.wikipedia.history.HistoryEntry
import org.wikipedia.util.log.L

class WikiWrappedDialog(activity: Activity) : MaterialAlertDialogBuilder(activity) {
    private val binding = DialogWikiWrappedBinding.inflate(activity.layoutInflater)
    private var dialog: AlertDialog? = null
    private val disposables = CompositeDisposable()

    init {
        setView(binding.root)
        Glide.with(context)
            .load("https://media.itsnicethat.com/original_images/22_Wrapped_Shapes.gif")
            .into(DrawableImageViewTarget(binding.wrappedGifView))
        fetchHistoryItems()
    }
    private fun fetchHistoryItems() {
        disposables.clear()
        disposables.add(Observable.fromCallable { AppDatabase.instance.historyEntryWithImageDao().filterHistoryItems("") }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ items -> onLoadItemsFinished(items) }) { t ->
                L.e(t)
                onLoadItemsFinished(emptyList())
            })
    }

    private fun onLoadItemsFinished(items: List<Any>) {
        val list = mutableListOf<String>()
        for (listItem in items) {
            if (listItem is HistoryEntry)
                list.add(listItem.apiTitle)
        }
        // Todo: Do this after the api call
        /*binding.wrappedRecycler.layoutManager = LinearLayoutManager(context)
        binding.wrappedRecycler.adapter =
            CustomWrappedAdapter(mutableListOf(wrappedList[0]), activity)
        runBlocking { showSLowProgress(CoroutineScope(Dispatchers.Main)) }*/
    }

    private suspend fun showSLowProgress(scope: CoroutineScope) {
        scope.launch {
            delay(2000)
            for (i in 1 until wrappedList.size) {
                (binding.wrappedRecycler.adapter as CustomWrappedAdapter).addToList(wrappedList[i])
                delay(2000)
            }
        }
    }

    class CustomWrappedAdapter(
        private val items: MutableList<String>, private val context: Context
    ) :
        RecyclerView.Adapter<CustomWrappedAdapter.ViewHolder>() {

        private var lastPosition = -1

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var text: TextView

            var container: FrameLayout

            init {
                container = itemView.findViewById<View>(R.id.item_layout_container) as FrameLayout
                text = itemView.findViewById<View>(R.id.item_layout_text) as TextView
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v: View = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_wrapped_item, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int {
            return items.size
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.text.text = items[position]

            setAnimation(holder.itemView, position)
        }

        private fun setAnimation(viewToAnimate: View, position: Int) {
            // If the bound view wasn't previously displayed on screen, it's animated
            if (position > lastPosition) {
                val animation: Animation =
                    AnimationUtils.loadAnimation(context, android.R.anim.slide_in_left)
                viewToAnimate.startAnimation(animation)
                lastPosition = position
            }
        }

        fun addToList(item: String) {
            items.add(item)
            notifyDataSetChanged()
        }
    }

    override fun show(): AlertDialog {
        dialog = super.show()
        return dialog!!
    }

    companion object {
        val wrappedList = mutableListOf(
            "Articles", "Edits", "Contributions"
        )
    }
}