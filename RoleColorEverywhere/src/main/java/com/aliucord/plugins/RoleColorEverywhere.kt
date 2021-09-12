package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PinePatchFn
import com.aliucord.plugins.rolecoloreverywhere.PluginSettings
import com.aliucord.plugins.rolecoloreverywhere.ReflectionExtensions
import com.aliucord.plugins.rolecoloreverywhere.ReflectionExtensions.binding
import com.aliucord.plugins.rolecoloreverywhere.ReflectionExtensions.mDraweeStringBuilder
import com.aliucord.wrappers.ChannelWrapper.Companion.isDM
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import com.discord.widgets.chat.input.autocomplete.adapter.AutocompleteItemViewHolder
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.overlay.ChatTypingModel
import com.discord.widgets.chat.overlay.WidgetChatOverlay
import com.discord.widgets.chat.overlay.`ChatTypingModel$Companion$getTypingUsers$1$1`
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

@AliucordPlugin
class RoleColorEverywhere : Plugin() {
    private val typingUsers = HashMap<String, Int>()

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    @Suppress("UNCHECKED_CAST")
    override fun start(context: Context) {
        ReflectionExtensions.init()
        val guildStore = StoreStream.getGuilds()

        if (settings.getBool("typingText", true)) {
            patcher.patch(`ChatTypingModel$Companion$getTypingUsers$1$1`::class.java.getDeclaredMethod("call", Map::class.java, Map::class.java), PinePatchFn {
                typingUsers.clear()

                val channel = StoreStream.getChannelsSelected().selectedChannel

                if (channel.isDM()) return@PinePatchFn

                val users = it.args[0] as Map<Long, User>
                val members = it.args[1] as Map<Long, GuildMember>

                members.forEach { (id, member) ->
                    val color = member.color
                    if (color != Color.BLACK) {
                        typingUsers[GuildMember.getNickOrUsername(member, users[id])] = color
                    }
                }
            })

            patcher.patch(WidgetChatOverlay.TypingIndicatorViewHolder::class.java.getDeclaredMethod("configureTyping", ChatTypingModel.Typing::class.java), PinePatchFn {
                val binding = (it.thisObject as WidgetChatOverlay.TypingIndicatorViewHolder).binding
                val textView = binding.root.findViewById<TextView>(Utils.getResId("chat_typing_users_typing", "id"))

                textView.apply {
                    text = SpannableString(text).apply {
                        typingUsers.forEach { (username, color) ->
                            val start = text.indexOf(username)
                            setSpan(ForegroundColorSpan(color), start, start + username.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            })
        }

        if (settings.getBool("userMentions", true)) {
            patcher.patch(UserMentionNode::class.java.getDeclaredMethod("renderUserMention", SpannableStringBuilder::class.java, UserMentionNode.RenderContext::class.java), object : MethodHook() {
                private var mentionLength: Int = 0

                override fun beforeCall(callFrame: Pine.CallFrame) {
                    mentionLength = (callFrame.args[0] as SpannableStringBuilder).length
                }

                override fun afterCall(callFrame: Pine.CallFrame) {
                    val userMentionNode = callFrame.thisObject as UserMentionNode<UserMentionNode.RenderContext>
                    val guild = guildStore.getGuild(StoreStream.getGuildSelected().selectedGuildId)
                    val member = guildStore.getMember(guild.id, userMentionNode.userId) ?: return

                    val foregroundColor = if (member.color == Color.BLACK) Color.WHITE else member.color
                    val backgroundColor = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(foregroundColor, Color.BLACK, 0.65f), 70)

                    with(callFrame.args[0] as SpannableStringBuilder) {
                        setSpan(ForegroundColorSpan(foregroundColor), mentionLength, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(BackgroundColorSpan(backgroundColor), mentionLength, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            })
        }

        if (settings.getBool("voiceChannel", true)) {
            patcher.patch(WidgetChannelsListAdapter.ItemVoiceUser::class.java.getDeclaredMethod("onConfigure", Int::class.java, ChannelListItem::class.java), PinePatchFn {
                val channelListItemVoiceUser = it.args[1] as ChannelListItemVoiceUser
                val binding = (it.thisObject as WidgetChannelsListAdapter.ItemVoiceUser).binding
                val textView = binding.root.findViewById<TextView>(Utils.getResId("channels_item_voice_user_name", "id"))
                val color = channelListItemVoiceUser.computed.color

                if (color != Color.BLACK) textView.setTextColor(color)
            })
        }

        if (settings.getBool("userMentionList", true)) {
            patcher.patch(AutocompleteItemViewHolder::class.java.getDeclaredMethod("bindUser", UserAutocompletable::class.java), PinePatchFn {
                val userAutocompletable = it.args[0] as UserAutocompletable
                val binding = (it.thisObject as AutocompleteItemViewHolder).binding

                val itemName = binding.root.findViewById<TextView>(Utils.getResId("chat_input_item_name", "id"))
                val color = userAutocompletable.guildMember.color

                if (color != Color.BLACK) itemName.setTextColor(color)
            })
        }

        if (settings.getBool("profileName", true)) {
            patcher.patch(UserProfileHeaderView::class.java.getDeclaredMethod("configurePrimaryName", UserProfileHeaderViewModel.ViewState.Loaded::class.java), PinePatchFn { callFrame ->
                val loaded = callFrame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded

                loaded.guildMember?.let {
                    val textView = UserProfileHeaderView.`access$getBinding$p`(callFrame.thisObject as UserProfileHeaderView).root
                            .findViewById<com.facebook.drawee.span.SimpleDraweeSpanTextView>(Utils.getResId("username_text", "id"))

                    textView.apply {
                        if (it.color == Color.BLACK) return@PinePatchFn

                        val end = if (it.nick == null && !settings.getBool("profileTag", true))
                            loaded.user.username.length
                        else
                            i.length

                        i.setSpan(ForegroundColorSpan(it.color), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setDraweeSpanStringBuilder(i)
                    }
                }
            })
        }

        if (settings.getBool("messages", false)) {
            patcher.patch(WidgetChatListAdapterItemMessage::class.java, "processMessageText", arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java), PinePatchFn {
                val messageEntry = it.args[1] as MessageEntry
                val member = messageEntry.author ?: return@PinePatchFn

                if (member.color != Color.BLACK) {
                    val textView = it.args[0] as SimpleDraweeSpanTextView
                    textView.mDraweeStringBuilder.apply {
                        setSpan(ForegroundColorSpan(member.color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(this)
                    }
                }
            })
        }

        if (settings.getBool("status", true)) {
            patcher.patch(ChannelMembersListViewHolderMember::class.java, "bind", arrayOf(ChannelMembersListAdapter.Item.Member::class.java, Function0::class.java), PinePatchFn {
                val member = (it.args[0] as ChannelMembersListAdapter.Item.Member) ?: return@PinePatchFn
                val binding = (it.thisObject as ChannelMembersListViewHolderMember).binding

                if (member.color != Color.BLACK && member.color != null) {
                    val textView = binding.root
                        .findViewById<SimpleDraweeSpanTextView>(Utils.getResId("channel_members_list_item_game", "id"))

                    textView.mDraweeStringBuilder.apply {
                        setSpan(ForegroundColorSpan(member.color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(this)
                    }
                }
            })

            patcher.patch(UserProfileHeaderView::class.java.getDeclaredMethod("updateViewState", UserProfileHeaderViewModel.ViewState.Loaded::class.java), PinePatchFn { callFrame ->
                val loaded = callFrame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded

                loaded.guildMember?.let {
                    val textView = UserProfileHeaderView.`access$getBinding$p`(callFrame.thisObject as UserProfileHeaderView).root
                        .findViewById<SimpleDraweeSpanTextView>(Utils.getResId("user_profile_header_custom_status", "id"))
                        ?: return@PinePatchFn
                    
                    textView.apply {
                        if (it.color == Color.BLACK) return@PinePatchFn

                        mDraweeStringBuilder.setSpan(ForegroundColorSpan(it.color), 0, mDraweeStringBuilder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(mDraweeStringBuilder)
                    }
                }
            })
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}
