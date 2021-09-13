package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
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
import com.discord.utilities.textprocessing.FontColorSpan
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.input.autocomplete.*
import com.discord.widgets.chat.input.autocomplete.adapter.AutocompleteItemViewHolder
import com.discord.widgets.chat.input.models.MentionInputModel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.overlay.ChatTypingModel
import com.discord.widgets.chat.overlay.WidgetChatOverlay
import com.discord.widgets.chat.overlay.`ChatTypingModel$Companion$getTypingUsers$1$1`
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

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

                if (StoreStream.getChannelsSelected().selectedChannel.isDM()) return@PinePatchFn

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
                            if (start != -1) setSpan(ForegroundColorSpan(color), start, start + username.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

            patcher.patch(AutocompleteViewModel::class.java.getDeclaredMethod("generateSpanUpdates", MentionInputModel::class.java), PinePatchFn {
                val res = it.result as InputEditTextAction.ReplaceCharacterStyleSpans
                val mentionInputModel = it.args[0] as MentionInputModel

                mentionInputModel.inputMentionsMap.forEach { (k, v) ->
                    if (v !is UserAutocompletable) return@PinePatchFn

                    val color = v.guildMember?.color ?: return@PinePatchFn
                    if (color != Color.BLACK) res.spans[k] = listOf(FontColorSpan(color), StyleSpan(1))
                }
            })
        }

        if (settings.getBool("voiceChannel", true)) {
            patcher.patch(WidgetChannelsListAdapter.ItemVoiceUser::class.java.getDeclaredMethod("onConfigure", Int::class.java, ChannelListItem::class.java), PinePatchFn {
                val channelListItemVoiceUser = it.args[1] as ChannelListItemVoiceUser
                val color = channelListItemVoiceUser.computed.color

                if (color != Color.BLACK) {
                    val root = (it.thisObject as WidgetChannelsListAdapter.ItemVoiceUser).binding.root
                    root.findViewById<TextView>(Utils.getResId("channels_item_voice_user_name", "id")).setTextColor(color)
                }
            })
        }

        if (settings.getBool("userMentionList", true)) {
            patcher.patch(AutocompleteItemViewHolder::class.java.getDeclaredMethod("bindUser", UserAutocompletable::class.java), PinePatchFn {
                val userAutocompletable = it.args[0] as UserAutocompletable
                val color = userAutocompletable.guildMember?.color ?: return@PinePatchFn

                if (color != Color.BLACK) {
                    val root = (it.thisObject as AutocompleteItemViewHolder).binding.root
                    root.findViewById<TextView>(Utils.getResId("chat_input_item_name", "id")).setTextColor(color)
                }
            })
        }

        if (settings.getBool("profileName", true)) {
            patcher.patch(UserProfileHeaderView::class.java.getDeclaredMethod("configurePrimaryName", UserProfileHeaderViewModel.ViewState.Loaded::class.java), PinePatchFn { callFrame ->
                val loaded = callFrame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
                val guildMember = loaded.guildMember ?: return@PinePatchFn

                if (guildMember.color != Color.BLACK) {
                    val textView = UserProfileHeaderView.`access$getBinding$p`(callFrame.thisObject as UserProfileHeaderView).root
                            .findViewById<com.facebook.drawee.span.SimpleDraweeSpanTextView>(Utils.getResId("username_text", "id"))

                    textView.apply {
                        val end = if (guildMember.nick == null && !settings.getBool("profileTag", true))
                            loaded.user.username.length
                        else
                            i.length

                        i.setSpan(ForegroundColorSpan(guildMember.color), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setDraweeSpanStringBuilder(i)
                    }
                }
            })
        }

        if (settings.getBool("messages", false)) {
            patcher.patch(WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod("processMessageText", SimpleDraweeSpanTextView::class.java, MessageEntry::class.java), PinePatchFn {
                val messageEntry = it.args[1] as MessageEntry
                val member = messageEntry.author ?: return@PinePatchFn

                if (member.color != Color.BLACK) {
                    val textView = it.args[0] as SimpleDraweeSpanTextView
                    textView.mDraweeStringBuilder?.apply {
                        setSpan(ForegroundColorSpan(member.color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(this)
                    }
                }
            })
        }

        if (settings.getBool("status", true)) {
            patcher.patch(ChannelMembersListViewHolderMember::class.java.getDeclaredMethod("bind", ChannelMembersListAdapter.Item.Member::class.java, Function0::class.java), PinePatchFn {
                val member = it.args[0] as ChannelMembersListAdapter.Item.Member
                val color = member.color ?: return@PinePatchFn

                if (color != Color.BLACK) {
                    val root = (it.thisObject as ChannelMembersListViewHolderMember).binding.root
                    val textView = root.findViewById<SimpleDraweeSpanTextView>(Utils.getResId("channel_members_list_item_game", "id"))

                    textView.mDraweeStringBuilder?.apply {
                        setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(this)
                    }
                }
            })

            patcher.patch(UserProfileHeaderView::class.java.getDeclaredMethod("updateViewState", UserProfileHeaderViewModel.ViewState.Loaded::class.java), PinePatchFn { callFrame ->
                val guildMember = (callFrame.args[0] as UserProfileHeaderViewModel.ViewState.Loaded).guildMember ?: return@PinePatchFn

                if (guildMember.color != Color.BLACK) {
                    val textView = UserProfileHeaderView.`access$getBinding$p`(callFrame.thisObject as UserProfileHeaderView).root
                            .findViewById<SimpleDraweeSpanTextView>(Utils.getResId("user_profile_header_custom_status", "id"))

                    textView.mDraweeStringBuilder?.apply {
                        setSpan(ForegroundColorSpan(guildMember.color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        textView.setDraweeSpanStringBuilder(this)
                    }
                }
            })
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}