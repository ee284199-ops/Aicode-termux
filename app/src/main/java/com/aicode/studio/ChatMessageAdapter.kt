package com.aicode.studio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(
    val role: String,           // "user" | "ai" | "system" | "compact_separator"
    val content: String,        // main text (for compact_separator: header label e.g. "12개 메시지 압축됨")
    var thought: String? = null,
    var thoughtExpanded: Boolean = false,
    var compactedText: String? = null,   // compact_separator 확장 시 표시할 텍스트
    var compactExpanded: Boolean = false // compact_separator 펼침 상태
)

class ChatMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val TYPE_USER    = 0
        private const val TYPE_AI      = 1
        private const val TYPE_SYSTEM  = 2
        private const val TYPE_COMPACT = 3
        /** payload: thought 텍스트만 갱신 — 레이아웃 전체 rebind 없이 흔들림 방지 */
        const val PAYLOAD_THOUGHT  = "thought"
        const val PAYLOAD_CONTENT  = "content"
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    inner class AI_VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val thinkingRoot: View = view.findViewById(R.id.thinkingRoot)
        val btnToggleThinking: View = view.findViewById(R.id.btnToggleThinking)
        val tvThinking: TextView = view.findViewById(R.id.tvThinking)
        val tvThinkingArrow: TextView = view.findViewById(R.id.tvThinkingArrow)

        init {
            btnToggleThinking.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    messages[pos].thoughtExpanded = !messages[pos].thoughtExpanded
                    notifyItemChanged(pos)
                }
            }
        }
    }

    inner class Compact_VH(view: View) : RecyclerView.ViewHolder(view) {
        val compactHeader: View = view.findViewById(R.id.compactHeader)
        val tvCompactLabel: TextView = view.findViewById(R.id.tvCompactLabel)
        val tvCompactedContent: TextView = view.findViewById(R.id.tvCompactedContent)

        init {
            compactHeader.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    messages[pos].compactExpanded = !messages[pos].compactExpanded
                    notifyItemChanged(pos)
                }
            }
        }
    }

    override fun getItemViewType(position: Int) = when (messages[position].role) {
        "user"              -> TYPE_USER
        "ai"                -> TYPE_AI
        "compact_separator" -> TYPE_COMPACT
        else                -> TYPE_SYSTEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            TYPE_USER    -> R.layout.item_chat_user
            TYPE_AI      -> R.layout.item_chat_ai
            TYPE_COMPACT -> R.layout.item_chat_compact
            else         -> R.layout.item_chat_system
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return when (viewType) {
            TYPE_AI      -> AI_VH(view)
            TYPE_COMPACT -> Compact_VH(view)
            else         -> VH(view)
        }
    }

    // 부분 업데이트 — payload가 있으면 해당 뷰만 갱신 (height 변경 최소화)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { onBindViewHolder(holder, position); return }
        val msg = messages[position]
        for (p in payloads) when (p) {
            PAYLOAD_THOUGHT -> if (holder is AI_VH) {
                val t = msg.thought
                holder.thinkingRoot.visibility = if (!t.isNullOrEmpty()) View.VISIBLE else View.GONE
                holder.tvThinking.text = t ?: ""
            }
            PAYLOAD_CONTENT -> when (holder) {
                is AI_VH -> holder.tvMessage.text = msg.content
                is VH    -> holder.tvMessage.text = msg.content
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is AI_VH -> {
                holder.tvMessage.text = msg.content
                if (!msg.thought.isNullOrEmpty()) {
                    holder.thinkingRoot.visibility = View.VISIBLE
                    holder.tvThinking.text = msg.thought
                    holder.tvThinking.visibility = if (msg.thoughtExpanded) View.VISIBLE else View.GONE
                    holder.tvThinkingArrow.text = if (msg.thoughtExpanded) " ▲" else " ▼"
                } else {
                    holder.thinkingRoot.visibility = View.GONE
                }
            }
            is Compact_VH -> {
                val hasContent = !msg.compactedText.isNullOrBlank()
                val expanded = msg.compactExpanded && hasContent
                val toggleHint = when {
                    !hasContent        -> ""
                    expanded           -> " (접기 ▲)"
                    else               -> " (펼치기 ▼)"
                }
                holder.tvCompactLabel.text = "─  ${msg.content}$toggleHint  ─"
                if (hasContent) {
                    holder.tvCompactedContent.text = msg.compactedText
                    holder.tvCompactedContent.visibility = if (expanded) View.VISIBLE else View.GONE
                } else {
                    holder.tvCompactedContent.visibility = View.GONE
                }
            }
            is VH -> {
                holder.tvMessage.text = msg.content
            }
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: ChatMessage): Int {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        return messages.size - 1
    }

    /** 지정 위치에 메시지 삽입 (compact separator 삽입에 사용) */
    fun insertMessage(position: Int, msg: ChatMessage) {
        val pos = position.coerceIn(0, messages.size)
        messages.add(pos, msg)
        notifyItemInserted(pos)
    }

    fun updateMessage(index: Int, content: String) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = content)
            notifyItemChanged(index, PAYLOAD_CONTENT)
        }
    }

    fun updateThought(index: Int, thought: String) {
        if (index in messages.indices && messages[index].role == "ai") {
            messages[index].thought = thought
            // payload 부분 업데이트 → 높이 변경 최소화 → 흔들림 방지
            notifyItemChanged(index, PAYLOAD_THOUGHT)
        }
    }

    fun appendToMessage(index: Int, addition: String) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = messages[index].content + addition)
            notifyItemChanged(index)
        }
    }

    fun getMessage(index: Int): String =
        if (index in messages.indices) messages[index].content else ""

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }
}
