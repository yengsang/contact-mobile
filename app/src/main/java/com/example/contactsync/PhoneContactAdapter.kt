package com.example.contactsync

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.contactsync.databinding.ItemContactBinding

class PhoneContactAdapter : RecyclerView.Adapter<PhoneContactAdapter.ContactViewHolder>() {

    private var contacts = listOf<PhoneContact>()

    fun submitList(newList: List<PhoneContact>) {
        contacts = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount() = contacts.size

    class ContactViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: PhoneContact) {
            binding.nameText.text = contact.name
            binding.phoneText.text = contact.phone
            binding.emailText.text = contact.email ?: "No email"
        }
    }
}
