package org.wikipedia.talk.template

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.analytics.eventplatform.PatrollerExperienceEvent
import org.wikipedia.databinding.FragmentTalkTemplatesBinding
import org.wikipedia.extensions.parcelable
import org.wikipedia.page.PageTitle
import org.wikipedia.talk.TalkReplyActivity
import org.wikipedia.talk.TalkTopicsActivity
import org.wikipedia.talk.db.TalkTemplate
import org.wikipedia.talk.template.TalkTemplatesActivity.Companion.EXTRA_TEMPLATE_MANAGEMENT
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.views.MultiSelectActionModeCallback

class TalkTemplatesFragment : Fragment(), MenuProvider {
    private var _binding: FragmentTalkTemplatesBinding? = null

    private val viewModel: TalkTemplatesViewModel by viewModels { TalkTemplatesViewModel.Factory(requireArguments()) }
    private val binding get() = _binding!!

    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var adapter: RecyclerAdapter
    private val selectedItems = mutableListOf<TalkTemplate>()
    private var actionMode: ActionMode? = null
    private val multiSelectCallback = MultiSelectCallback()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        _binding = FragmentTalkTemplatesBinding.inflate(inflater, container, false)

        (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
        (requireActivity() as AppCompatActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        (requireActivity() as AppCompatActivity).supportActionBar?.title =
            getString(if (viewModel.templateManagementMode) R.string.talk_warn_saved_messages else R.string.talk_warn)

        return binding.root
    }

    private val requestNewTemplate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadTalkTemplates()
            PatrollerExperienceEvent.logAction("save_message_toast", "pt_templates")
            FeedbackUtil.showMessage(this, R.string.talk_templates_new_message_saved)
        }
        if (result.resultCode == TalkReplyActivity.RESULT_EDIT_SUCCESS || result.resultCode == TalkReplyActivity.RESULT_SAVE_TEMPLATE) {
            val pageTitle = requireArguments().parcelable<PageTitle>(Constants.ARG_TITLE)!!
            val message = if (result.resultCode == TalkReplyActivity.RESULT_EDIT_SUCCESS) {
                PatrollerExperienceEvent.logAction("publish_message_toast", "pt_warning_messages")
                R.string.talk_warn_submitted
            } else {
                PatrollerExperienceEvent.logAction("publish_message_saved_toast", "pt_warning_messages")
                R.string.talk_warn_submitted_and_saved
            }
            val snackbar = FeedbackUtil.makeSnackbar(requireActivity(), getString(message))
            snackbar.setAction(R.string.patroller_tasks_patrol_edit_snackbar_view) {
                PatrollerExperienceEvent.logAction("publish_message_view_click", "pt_warning_messages")
                startActivity(TalkTopicsActivity.newIntent(requireContext(), pageTitle, Constants.InvokeSource.DIFF_ACTIVITY))
            }
            snackbar.show()
        }
    }

    private val requestEditTemplate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.loadTalkTemplates()
            PatrollerExperienceEvent.logAction("update_message_toast", "pt_templates")
            FeedbackUtil.showMessage(this, R.string.talk_templates_edit_message_updated)
        }
        if (result.resultCode == TalkReplyActivity.RESULT_EDIT_SUCCESS || result.resultCode == TalkReplyActivity.RESULT_SAVE_TEMPLATE) {
            val pageTitle = requireArguments().parcelable<PageTitle>(Constants.ARG_TITLE)!!
            val message = if (result.resultCode == TalkReplyActivity.RESULT_EDIT_SUCCESS) {
                PatrollerExperienceEvent.logAction("publish_message_toast", "pt_warning_messages")
                R.string.talk_warn_submitted
            } else {
                PatrollerExperienceEvent.logAction("publish_message_saved_toast", "pt_warning_messages")
                R.string.talk_warn_submitted_and_saved
            }
            val snackbar = FeedbackUtil.makeSnackbar(requireActivity(), getString(message))
            snackbar.setAction(R.string.patroller_tasks_patrol_edit_snackbar_view) {
                PatrollerExperienceEvent.logAction("publish_message_view_click", "pt_warning_messages")
                startActivity(TalkTopicsActivity.newIntent(requireContext(), pageTitle, Constants.InvokeSource.DIFF_ACTIVITY))
            }
            snackbar.show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        binding.talkTemplatesErrorView.retryClickListener = View.OnClickListener { viewModel.loadTalkTemplates() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.uiState.collect {
                        when (it) {
                            is TalkTemplatesViewModel.UiState.Loading -> onLoading()
                            is TalkTemplatesViewModel.UiState.Success -> onSuccess()
                            is TalkTemplatesViewModel.UiState.Error -> onError(it.throwable)
                        }
                    }
                }

                launch {
                    viewModel.actionState.collect {
                        when (it) {
                            is TalkTemplatesViewModel.ActionState.Deleted -> onDeleted(it.size)
                            is TalkTemplatesViewModel.ActionState.Error -> onActionError(it.throwable)
                        }
                    }
                }
            }
        }

        binding.addTemplateFab.setOnClickListener {
            val pageTitle = requireArguments().parcelable<PageTitle>(Constants.ARG_TITLE)!!
            requestNewTemplate.launch(TalkReplyActivity.newIntent(requireContext(), pageTitle, null,
                null, invokeSource = Constants.InvokeSource.DIFF_ACTIVITY, fromDiff = true, templateManagementMode = viewModel.templateManagementMode))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_talk_templates, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.menu_edit_messages -> {
                true
            }
            else -> false
        }
    }

    private fun setRecyclerView() {
        binding.talkTemplatesRecyclerView.setHasFixedSize(true)
        adapter = RecyclerAdapter()
        binding.talkTemplatesRecyclerView.adapter = adapter
        binding.talkTemplatesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        itemTouchHelper = ItemTouchHelper(RearrangeableItemTouchHelperCallback(adapter))
        itemTouchHelper.attachToRecyclerView(binding.talkTemplatesRecyclerView)
    }

    private fun onLoading() {
        binding.talkTemplatesEmptyContainer.visibility = View.GONE
        binding.talkTemplatesRecyclerView.visibility = View.GONE
        binding.talkTemplatesErrorView.visibility = View.GONE
    }

    private fun onSuccess() {
        setRecyclerView()
        requireActivity().invalidateOptionsMenu()
        binding.talkTemplatesEmptyContainer.isVisible = viewModel.talkTemplatesList.isEmpty()
        binding.talkTemplatesErrorView.visibility = View.GONE
        binding.talkTemplatesProgressBar.visibility = View.GONE
        binding.talkTemplatesRecyclerView.isVisible = viewModel.talkTemplatesList.isNotEmpty()
        if (binding.talkTemplatesEmptyContainer.isVisible) {
            PatrollerExperienceEvent.logAction("templates_empty_impression", "pt_templates")
        }
    }

    private fun onDeleted(size: Int) {
        PatrollerExperienceEvent.logAction("message_deleted_toast", "pt_templates")
        val messageStr = resources.getQuantityString(R.plurals.talk_templates_message_deleted, size)
        FeedbackUtil.showMessage(this, messageStr)
        binding.talkTemplatesEmptyContainer.isVisible = viewModel.talkTemplatesList.isEmpty()
        binding.talkTemplatesRecyclerView.isVisible = viewModel.talkTemplatesList.isNotEmpty()
        if (binding.talkTemplatesEmptyContainer.isVisible) {
            PatrollerExperienceEvent.logAction("templates_empty_impression", "pt_templates")
        }
        actionMode?.finish()
        unselectAllTalkTemplates()
    }

    private fun onActionError(t: Throwable) {
        FeedbackUtil.showMessage(this, t.toString())
    }

    private fun onError(t: Throwable) {
        binding.talkTemplatesProgressBar.visibility = View.GONE
        binding.talkTemplatesErrorView.setError(t)
        binding.talkTemplatesErrorView.visibility = View.VISIBLE
    }

    internal inner class TalkTemplatesItemViewHolder(val templatesItemView: TalkTemplatesItemView) : RecyclerView.ViewHolder(templatesItemView.rootView) {
        fun bindItem(item: TalkTemplate, position: Int) {
            templatesItemView.setContents(item, position)
        }
    }

    internal inner class RecyclerAdapter : RecyclerView.Adapter<TalkTemplatesItemViewHolder>(), TalkTemplatesItemView.Callback {

        private var checkboxEnabled = false

        override fun getItemCount(): Int {
            return viewModel.talkTemplatesList.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TalkTemplatesItemViewHolder {
            return TalkTemplatesItemViewHolder(TalkTemplatesItemView(requireContext()))
        }

        override fun onBindViewHolder(holder: TalkTemplatesItemViewHolder, position: Int) {
            val talkTemplate = viewModel.talkTemplatesList[position]
            holder.bindItem(talkTemplate, position)
            holder.templatesItemView.setCheckBoxEnabled(checkboxEnabled)
            holder.templatesItemView.setCheckBoxChecked(selectedItems.contains(talkTemplate))
            holder.templatesItemView.setDragHandleEnabled(!checkboxEnabled)
        }

        override fun onViewAttachedToWindow(holder: TalkTemplatesItemViewHolder) {
            super.onViewAttachedToWindow(holder)
            holder.templatesItemView.setDragHandleTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> itemTouchHelper.startDrag(holder)
                    MotionEvent.ACTION_UP -> v.performClick()
                    else -> { }
                }
                false
            }
            holder.templatesItemView.callback = this
        }

        override fun onViewDetachedFromWindow(holder: TalkTemplatesItemViewHolder) {
            holder.templatesItemView.setDragHandleTouchListener(null)
            holder.templatesItemView.callback = null
            super.onViewDetachedFromWindow(holder)
        }

        override fun onClick(position: Int) {
            if (actionMode != null) {
                toggleSelectedItem(viewModel.talkTemplatesList[position])
                adapter.notifyItemChanged(position)
            } else {
                PatrollerExperienceEvent.logAction("edit_message_click", "pt_templates")
                val pageTitle = requireArguments().parcelable<PageTitle>(Constants.ARG_TITLE)!!
                requestEditTemplate.launch(TalkReplyActivity.newIntent(requireContext(), pageTitle, null, null, invokeSource = Constants.InvokeSource.DIFF_ACTIVITY, fromDiff = true, templateId = viewModel.talkTemplatesList[position].id, templateManagementMode = viewModel.templateManagementMode))
            }
        }

        override fun onCheckedChanged(position: Int) {
            toggleSelectedItem(viewModel.talkTemplatesList[position])
        }

        override fun onLongPress(position: Int) {
            if (actionMode == null) {
                beginRemoveItemsMode()
            }
            toggleSelectedItem(viewModel.talkTemplatesList[position])
            adapter.notifyItemChanged(position)
        }

        fun onMoveItem(oldPosition: Int, newPosition: Int) {
            viewModel.swapList(oldPosition, newPosition)
            viewModel.updateItemOrder()
            notifyItemMoved(oldPosition, newPosition)
        }

        fun onCheckboxEnabled(enabled: Boolean) {
            checkboxEnabled = enabled
        }
    }

    private fun setMultiSelectEnabled(enabled: Boolean) {
        adapter.onCheckboxEnabled(enabled)
        adapter.notifyItemRangeChanged(0, viewModel.talkTemplatesList.size)
        requireActivity().invalidateOptionsMenu()
    }

    private fun beginRemoveItemsMode() {
        (requireActivity() as AppCompatActivity).startSupportActionMode(multiSelectCallback)
        setMultiSelectEnabled(true)
    }

    private fun toggleSelectedItem(talkTemplate: TalkTemplate) {
        if (selectedItems.contains(talkTemplate)) {
            selectedItems.remove(talkTemplate)
        } else {
            selectedItems.add(talkTemplate)
        }
    }

    private fun unselectAllTalkTemplates() {
        selectedItems.clear()
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelectedTalkTemplates() {
        viewModel.deleteTemplates(selectedItems)
    }

    private inner class MultiSelectCallback : MultiSelectActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            super.onCreateActionMode(mode, menu)
            mode.setTitle(R.string.talk_templates_menu_remove_message)
            mode.menuInflater.inflate(R.menu.menu_action_mode_talk_templates, menu)
            actionMode = mode
            selectedItems.clear()
            return super.onCreateActionMode(mode, menu)
        }

        override fun onDeleteSelected() {
            if (selectedItems.size > 0) {
                PatrollerExperienceEvent.logAction("more_menu_remove_confirm", "pt_templates")
                val messageStr = resources.getQuantityString(
                    R.plurals.talk_templates_message_delete_description,
                    selectedItems.size
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialogTheme_Delete)
                    .setMessage(messageStr)
                    .setPositiveButton(R.string.talk_templates_edit_message_dialog_delete) { _, _ ->
                        PatrollerExperienceEvent.logAction("message_delete_click", "pt_templates")
                        deleteSelectedTalkTemplates()
                    }
                    .setNegativeButton(R.string.talk_templates_new_message_dialog_cancel) { _, _ ->
                        PatrollerExperienceEvent.logAction("message_delete_cancel", "pt_templates")
                    }
                    .show()
            } else {
                actionMode?.finish()
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            unselectAllTalkTemplates()
            setMultiSelectEnabled(false)
            actionMode = null
            super.onDestroyActionMode(mode)
        }
    }

    private inner class RearrangeableItemTouchHelperCallback constructor(private val adapter: RecyclerAdapter) : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean {
            return false
        }

        override fun isItemViewSwipeEnabled(): Boolean {
            return false
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.onMoveItem(source.absoluteAdapterPosition, target.absoluteAdapterPosition)
            return true
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            recyclerView.post {
                if (isAdded) {
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    companion object {
        fun newInstance(pageTitle: PageTitle?, templateManagement: Boolean = false): TalkTemplatesFragment {
            return TalkTemplatesFragment().apply {
                arguments = bundleOf(Constants.ARG_TITLE to pageTitle,
                    EXTRA_TEMPLATE_MANAGEMENT to templateManagement)
            }
        }
    }
}
