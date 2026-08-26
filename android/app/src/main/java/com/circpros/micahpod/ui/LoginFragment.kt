package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentLoginBinding
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.loginButton.setOnClickListener { attemptLogin() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun attemptLogin() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim().orEmpty()

        if (username.isBlank() || password.isBlank()) {
            binding.loginError.text = getString(R.string.error_generic)
            binding.loginError.isVisible = true
            return
        }

        binding.loginError.isVisible = false
        binding.loginProgress.isVisible = true
        binding.loginButton.isEnabled = false

        val repository = ServiceLocator.repository
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.login(username, password)
            }

            binding.loginProgress.isVisible = false
            binding.loginButton.isEnabled = true

            when (result) {
                is RepositoryResult.Success -> {
                    findNavController().navigate(R.id.action_login_to_feed)
                }
                is RepositoryResult.Error -> {
                    binding.loginError.text = result.message
                    binding.loginError.isVisible = true
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
