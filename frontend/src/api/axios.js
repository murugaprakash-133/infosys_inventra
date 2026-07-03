import axios from "axios";

const API_BASE_URL = "https://mindful-kindness-production-01b7.up.railway.app/api"; // Replace with your backend API base URL

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    "Content-Type": "application/json",
  },
});

// Request interceptor to add JWT token
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem("token");
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor for handling auth errors
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Only auto-redirect on 401 for non-auth endpoints
    // Auth endpoints (signin, signup, etc.) handle their own errors
    const isAuthEndpoint = error.config?.url?.startsWith("/auth/");
    
    if (error.response?.status === 401 && !isAuthEndpoint) {
      localStorage.removeItem("token");
      localStorage.removeItem("user");
      window.location.href = "/signin";
    } else if (error.response?.status === 403) {
      // 403 Forbidden - insufficient permissions
      console.error("Access denied: Insufficient permissions");
      // The component should handle displaying the error
    }
    
    return Promise.reject(error);
  }
);

// Auth API calls
export const authAPI = {
  signup: (data) => api.post("/auth/signup", data),
  signin: (data) => api.post("/auth/signin", data),
  forgotPassword: (data) => api.post("/auth/forgot-password", data),
  resetPassword: (data) => api.post("/auth/reset-password", data),
};

// Product API calls
export const productAPI = {
  getAll: () => api.get("/products"),
  getById: (id) => api.get(`/products/${id}`),
  search: (keyword) => api.get("/products/search", { params: { keyword } }),
  getByCategory: (category) => api.get(`/products/category/${category}`),
  getByStatus: (status) => api.get(`/products/status/${status}`),
  getCategories: () => api.get("/products/categories"),
  getLowStock: () => api.get("/products/low-stock"),
  getOutOfStock: () => api.get("/products/out-of-stock"),
  create: (data) => api.post("/products", data),
  update: (id, data) => api.put(`/products/${id}`, data),
  updateQuantity: (id, quantity) => api.patch(`/products/${id}/quantity`, null, { params: { quantity } }),
  delete: (id) => api.delete(`/products/${id}`),
};

export default api;
