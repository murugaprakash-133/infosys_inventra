package com.infosys.inventra.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.frontend.url:https://infosys-inventra.vercel.app/}")
    private String frontendUrl;

    /**
     * Send password reset email with reset token
     * @param email - Recipient email address
     * @param resetToken - Generated reset token
     */
    public void sendResetLink(String email, String resetToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject("Password Reset Request - Inventra");
            
            // Create reset password link
            String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
            
            // Email body
            String emailBody = "Dear User,\n\n"
                    + "You have requested to reset your password for your Inventra account.\n\n"
                    + "Please click the link below to reset your password:\n"
                    + resetLink + "\n\n"
                    + "This link will expire in 1 hour.\n\n"
                    + "If you did not request this password reset, please ignore this email.\n\n"
                    + "Best regards,\n"
                    + "Inventra Team";
            
            message.setText(emailBody);
            
            mailSender.send(message);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email: " + e.getMessage());
        }
    }
}
