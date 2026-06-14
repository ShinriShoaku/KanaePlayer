package ame.project.kanae

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ame.project.kanae.databinding.ActivityAdminListBinding

class AdminListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminListBinding
    private lateinit var adapter: AdminListAdapter
    private val adminList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadAdmins()
        setupRecyclerView()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddAdmin.setOnClickListener {
            val user = binding.etAdminUsername.text.toString().trim().removePrefix("@")
            if (user.isNotEmpty()) {
                if (!adminList.contains(user)) {
                    adminList.add(user)
                    saveAdmins()
                    adapter.notifyDataSetChanged()
                    binding.etAdminUsername.text?.clear()
                } else {
                    Toast.makeText(this, "User already in list", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = AdminListAdapter(adminList) { user ->
            adminList.remove(user)
            saveAdmins()
            adapter.notifyDataSetChanged()
        }
        binding.rvAdminList.layoutManager = LinearLayoutManager(this)
        binding.rvAdminList.adapter = adapter
    }

    private fun loadAdmins() {
        val prefs = getSharedPreferences("ytplayer_prefs", MODE_PRIVATE)
        val saved = prefs.getString("authorized_users", "") ?: ""
        if (saved.isNotEmpty()) {
            adminList.clear()
            adminList.addAll(saved.split(",").filter { it.isNotBlank() })
        }
    }

    private fun saveAdmins() {
        val prefs = getSharedPreferences("ytplayer_prefs", MODE_PRIVATE)
        prefs.edit().putString("authorized_users", adminList.joinToString(",")).apply()
    }

    class AdminListAdapter(
        private val users: List<String>,
        private val onRemove: (String) -> Unit
    ) : RecyclerView.Adapter<AdminListAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvUser: TextView = v.findViewById(R.id.tvAdminUsername)
            val btnRemove: ImageButton = v.findViewById(R.id.btnRemoveAdmin)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = users[position]
            holder.tvUser.text = "@$user"
            holder.btnRemove.setOnClickListener { onRemove(user) }
        }

        override fun getItemCount() = users.size
    }
}
