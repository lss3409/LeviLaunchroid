package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.StructureFileItem;

import java.util.ArrayList;
import java.util.List;

public class StructureFilesAdapter extends RecyclerView.Adapter<StructureFilesAdapter.ViewHolder> {

    public interface OnStructureActionListener {
        void onDelete(StructureFileItem structure);
    }

    private List<StructureFileItem> structures = new ArrayList<>();
    private OnStructureActionListener listener;

    public StructureFilesAdapter(OnStructureActionListener listener) {
        this.listener = listener;
    }

    public void updateData(List<StructureFileItem> structures) {
        this.structures = structures != null ? structures : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_structure_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StructureFileItem structure = structures.get(position);
        holder.nameText.setText(structure.getName());
        holder.sizeText.setText(structure.getFormattedSize());

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(structure);
        });
    }

    @Override
    public int getItemCount() {
        return structures.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameText;
        TextView sizeText;
        Button deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.structure_name);
            sizeText = itemView.findViewById(R.id.structure_size);
            deleteButton = itemView.findViewById(R.id.structure_delete_button);
        }
    }
}
